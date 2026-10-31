// Ядро mihomo внутри процесса приложения (libhalo.so). Так ядро получает дескриптор TUN от VpnService
// напрямую: отдельному процессу Android дескриптор не передаёт.
package main

/*
#cgo LDFLAGS: -llog
#include <jni.h>
#include <stdlib.h>
void halo_log(int prio, const char* msg);
const char* halo_get(JNIEnv* env, jstring s);
char* halo_utf8(JNIEnv* env, jstring s, int* len);
void halo_release(JNIEnv* env, jstring s, const char* c);
jstring halo_new(JNIEnv* env, const char* c);
char* halo_package(int proto, const char* src, int sport, const char* dst, int dport);
void halo_emit(int level, const char* msg);
*/
import "C"

import (
	"errors"
	"fmt"
	"net/netip"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"unsafe"

	"github.com/metacubex/mihomo/component/process"
	"github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/hub"
	"github.com/metacubex/mihomo/hub/executor"
	"github.com/metacubex/mihomo/log"
)

var (
	mu       sync.Mutex
	initOnce sync.Once

	// Последние строки журнала ядра — для экрана «Соединения → Журнал».
	ringMu sync.Mutex
	ring   = make([]string, 0, ringSize)
)

const ringSize = 400

var errUnknownApp = errors.New("приложение не определено")

// Подробный журнал: строки INFO ядра (каждое соединение) тоже уходят в журнал приложения.
var verbose atomic.Bool

// Журнал ядра: последние строки — в память (для экрана в приложении), предупреждения и ошибки — ещё и в logcat.
func collectLogs() {
	for ev := range log.Subscribe() {
		if ev.LogLevel == log.DEBUG {
			continue
		}
		line := fmt.Sprintf("%s %s %s", time.Now().Format("15:04:05"), levelMark(ev.LogLevel), ev.Payload)
		ringMu.Lock()
		if len(ring) == ringSize {
			copy(ring, ring[1:])
			ring = ring[:ringSize-1]
		}
		ring = append(ring, line)
		ringMu.Unlock()
		// В общий журнал приложения (HaloLog): предупреждения и ошибки всегда, остальное — в подробном режиме.
		// Уровни: 1 — подробно, 2 — инфо, 3 — предупреждение, 4 — ошибка.
		level := 0
		switch ev.LogLevel {
		case log.WARNING:
			level = 3
		case log.ERROR:
			level = 4
		default:
			if strings.Contains(ev.Payload, "[TCP]") || strings.Contains(ev.Payload, "[UDP]") {
				if verbose.Load() {
					level = 1
				}
			} else {
				level = 2 // запуск, провайдеры, гео-базы — важно для разбора проблем
			}
		}
		if level > 0 {
			c := C.CString(ev.Payload)
			C.halo_emit(C.int(level), c)
			C.free(unsafe.Pointer(c))
		}
	}
}

func levelMark(l log.LogLevel) string {
	switch l {
	case log.WARNING:
		return "W"
	case log.ERROR:
		return "E"
	default:
		return "I"
	}
}

// Какому приложению принадлежит соединение: спрашиваем у Android (getConnectionOwnerUid доступен VPN-приложению).
// Нужно для правил PROCESS-NAME — «Заблокировать сеть» у приложения.
func packageOf(m *constant.Metadata) (string, error) {
	if m.RawSrcAddr == nil || m.RawDstAddr == nil {
		return "", errUnknownApp
	}
	src, err1 := netip.ParseAddrPort(m.RawSrcAddr.String())
	dst, err2 := netip.ParseAddrPort(m.RawDstAddr.String())
	if err1 != nil || err2 != nil {
		return "", errUnknownApp
	}
	proto := 6
	if m.NetWork == constant.UDP {
		proto = 17
	}
	cs := C.CString(src.Addr().Unmap().String())
	cd := C.CString(dst.Addr().Unmap().String())
	defer C.free(unsafe.Pointer(cs))
	defer C.free(unsafe.Pointer(cd))
	r := C.halo_package(C.int(proto), cs, C.int(src.Port()), cd, C.int(dst.Port()))
	if r == nil {
		return "", errUnknownApp
	}
	defer C.free(unsafe.Pointer(r))
	return C.GoString(r), nil
}

func goString(env *C.JNIEnv, s C.jstring) string {
	var n C.int
	c := C.halo_utf8(env, s, &n)
	if c == nil {
		return ""
	}
	defer C.free(unsafe.Pointer(c))
	return C.GoStringN(c, n)
}

func javaString(env *C.JNIEnv, s string) C.jstring {
	c := C.CString(s)
	defer C.free(unsafe.Pointer(c))
	return C.halo_new(env, c)
}

// Запускает ядро или применяет новый конфиг к работающему. Возвращает текст ошибки или пустую строку.
//
//export Java_app_halo_vpn_core_Core_nativeApply
func Java_app_halo_vpn_core_Core_nativeApply(env *C.JNIEnv, _ C.jclass, home C.jstring, config C.jstring) C.jstring {
	mu.Lock()
	defer mu.Unlock()
	initOnce.Do(func() {
		go collectLogs()
		process.DefaultPackageNameResolver = packageOf
	})
	dir := goString(env, home)
	buf := []byte(goString(env, config))
	constant.SetHomeDir(dir)
	constant.SetConfig(filepath.Join(dir, "config.yaml"))
	_ = os.WriteFile(filepath.Join(dir, "config.yaml"), buf, 0o600)
	if err := hub.Parse(buf); err != nil {
		return javaString(env, err.Error())
	}
	return javaString(env, "")
}

//export Java_app_halo_vpn_core_Core_nativeStop
func Java_app_halo_vpn_core_Core_nativeStop(env *C.JNIEnv, _ C.jclass) {
	mu.Lock()
	defer mu.Unlock()
	executor.Shutdown()
}

// Последние строки журнала ядра, по одной на строку.
//
//export Java_app_halo_vpn_core_Core_nativeLogs
func Java_app_halo_vpn_core_Core_nativeLogs(env *C.JNIEnv, _ C.jclass) C.jstring {
	ringMu.Lock()
	text := strings.Join(ring, "\n")
	ringMu.Unlock()
	return javaString(env, text)
}

//export Java_app_halo_vpn_core_Core_nativeSetVerbose
func Java_app_halo_vpn_core_Core_nativeSetVerbose(env *C.JNIEnv, _ C.jclass, on C.jboolean) {
	verbose.Store(on != 0)
}

func main() {}
