// Package xraybridge is the thin gomobile-friendly surface the Android app uses to run
// Xray-core in-process. Everything exported here must stay gomobile-compatible
// (basic types, error, and interfaces implemented on the Java side).
package xraybridge

import (
	"errors"
	"fmt"
	"os"
	"runtime/debug"
	"strings"
	"sync"
	"syscall"

	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/transport/internet"

	// Registers every protocol, transport, router, DNS, stats ... that the JSON config can name.
	_ "github.com/xtls/xray-core/main/distro/all"
	// Registers the "json" config loader.
	_ "github.com/xtls/xray-core/main/json"
)

// Protector is implemented by the Android VpnService: Protect(fd) keeps a socket out of the
// VPN. Xray runs in the same process as the per-app VPN, so without it Xray's own outbound
// connections would be routed back into the tunnel and loop forever.
type Protector interface {
	Protect(fd int) bool
}

var (
	mu        sync.Mutex
	instance  *core.Instance
	protector Protector
	hooksOnce sync.Once
	hooksErr  error
)

// SetProtector installs (or, with nil, removes) the socket protector.
func SetProtector(p Protector) {
	mu.Lock()
	protector = p
	mu.Unlock()
}

// SetAssetDir points Xray at the directory that holds geoip.dat / geosite.dat.
func SetAssetDir(dir string) {
	if dir == "" {
		return
	}
	_ = os.Setenv("XRAY_LOCATION_ASSET", dir)
	_ = os.Setenv("xray.location.asset", dir)
}

// Version returns the embedded Xray-core version string.
func Version() string {
	return core.Version()
}

// IsRunning reports whether an Xray instance is currently started.
func IsRunning() bool {
	mu.Lock()
	defer mu.Unlock()
	return instance != nil
}

// CheckConfig parses and builds the JSON config without starting anything.
func CheckConfig(configJSON string) (err error) {
	defer func() {
		if r := recover(); r != nil {
			err = fmt.Errorf("xray config check panicked: %v", r)
		}
	}()
	_, err = core.LoadConfig("json", strings.NewReader(configJSON))
	return err
}

func registerHooks() error {
	hooksOnce.Do(func() {
		control := func(network, address string, conn syscall.RawConn) error {
			mu.Lock()
			p := protector
			mu.Unlock()
			if p == nil {
				return nil
			}
			var ok bool
			if err := conn.Control(func(fd uintptr) { ok = p.Protect(int(fd)) }); err != nil {
				return err
			}
			if !ok {
				return errors.New("VpnService.protect() refused the socket")
			}
			return nil
		}
		if err := internet.RegisterDialerController(control); err != nil {
			hooksErr = err
			return
		}
		hooksErr = internet.RegisterListenerController(control)
	})
	return hooksErr
}

// Start starts Xray with the given JSON config. A running instance is stopped first.
func Start(configJSON string) (err error) {
	defer func() {
		if r := recover(); r != nil {
			err = fmt.Errorf("xray start panicked: %v", r)
		}
	}()
	if err := registerHooks(); err != nil {
		return err
	}

	mu.Lock()
	defer mu.Unlock()

	if instance != nil {
		_ = instance.Close()
		instance = nil
	}

	// A phone has little memory to spare: collect earlier and keep a soft ceiling.
	debug.SetGCPercent(50)
	debug.SetMemoryLimit(384 << 20)

	inst, err := core.StartInstance("json", []byte(configJSON))
	if err != nil {
		return err
	}
	instance = inst
	return nil
}

// Stop stops the running instance (a no-op when none is running).
func Stop() (err error) {
	defer func() {
		if r := recover(); r != nil {
			err = fmt.Errorf("xray stop panicked: %v", r)
		}
	}()
	mu.Lock()
	defer mu.Unlock()
	if instance == nil {
		return nil
	}
	err = instance.Close()
	instance = nil
	debug.FreeOSMemory()
	return err
}
