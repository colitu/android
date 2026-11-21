// Android resolver for gecko-client. Added by scripts/build-gecko.sh with
// `go build -overlay` to the gecko-client program of the Android builds only;
// it is the app's file, not part of colitu-gecko, and the Gecko sources are
// not changed.
//
// gecko-client runs on Android as a static linux binary (CGO off). There is no
// /etc/resolv.conf there, so Go's resolver would ask 127.0.0.1:53 and every
// entry given by name would fail. The app passes the DNS servers of the
// phone's network (and a few public ones after them) in COLITU_DNS, comma
// separated host:port; queries go to them in turn. gecko-client's sockets are
// outside the VPN (the app's own UID is excluded from it), so these queries
// leave over the phone's network like the app's other direct traffic.
// Without COLITU_DNS nothing changes.

package main

import (
	"context"
	"net"
	"os"
	"strings"
	"sync/atomic"
	"time"
)

func init() {
	servers := colituAndroidDNSServers(os.Getenv("COLITU_DNS"))
	if len(servers) == 0 {
		return
	}
	var next atomic.Uint32
	dialer := &net.Dialer{Timeout: 4 * time.Second}
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			server := servers[int((next.Add(1)-1)%uint32(len(servers)))]
			return dialer.DialContext(ctx, network, server)
		},
	}
}

// colituAndroidDNSServers keeps the entries that are an IP address with a port.
func colituAndroidDNSServers(value string) []string {
	var servers []string
	for _, item := range strings.Split(value, ",") {
		host, port, err := net.SplitHostPort(strings.TrimSpace(item))
		if err != nil || port == "" || net.ParseIP(host) == nil {
			continue
		}
		servers = append(servers, net.JoinHostPort(host, port))
	}
	return servers
}
