package cloudflare

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

func TestProvisionDiscoversAndCreates(t *testing.T) {
	const (
		accountID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
		zoneID    = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
		tunnelID  = "11111111-2222-3333-4444-555555555555"
	)
	var mu sync.Mutex
	var calls []string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, request *http.Request) {
		if request.Header.Get("Authorization") != "Bearer api-token" {
			t.Fatalf("authorization header = %q", request.Header.Get("Authorization"))
		}
		mu.Lock()
		calls = append(calls, request.Method+" "+request.URL.RequestURI())
		mu.Unlock()
		switch {
		case request.Method == http.MethodGet && request.URL.Path == "/zones":
			if request.URL.Query().Get("name") == "example.com" {
				writeAPI(w, []any{map[string]any{
					"id": zoneID, "name": "example.com",
					"account": map[string]any{"id": accountID, "name": "account"},
				}})
			} else {
				writeAPI(w, []any{})
			}
		case request.Method == http.MethodGet && request.URL.Path == "/accounts/"+accountID+"/cfd_tunnel":
			writeAPI(w, []any{})
		case request.Method == http.MethodPost && request.URL.Path == "/accounts/"+accountID+"/cfd_tunnel":
			assertJSONField(t, request, "name", "mcp-example")
			writeAPI(w, map[string]any{"id": tunnelID, "name": "mcp-example", "config_src": "cloudflare"})
		case request.Method == http.MethodPut && request.URL.Path == "/accounts/"+accountID+"/cfd_tunnel/"+tunnelID+"/configurations":
			var body struct {
				Config struct {
					Ingress []map[string]any `json:"ingress"`
				} `json:"config"`
			}
			decodeRequest(t, request, &body)
			if len(body.Config.Ingress) != 2 || body.Config.Ingress[0]["hostname"] != "mcp.example.com" ||
				body.Config.Ingress[0]["service"] != "http://127.0.0.1:8765" {
				t.Fatalf("unexpected ingress: %+v", body.Config.Ingress)
			}
			writeAPI(w, map[string]any{})
		case request.Method == http.MethodGet && request.URL.Path == "/accounts/"+accountID+"/cfd_tunnel/"+tunnelID+"/token":
			writeAPI(w, "tunnel-token")
		case request.Method == http.MethodGet && request.URL.Path == "/zones/"+zoneID+"/dns_records":
			writeAPI(w, []any{})
		case request.Method == http.MethodPost && request.URL.Path == "/zones/"+zoneID+"/dns_records":
			assertJSONField(t, request, "content", tunnelID+".cfargotunnel.com")
			writeAPI(w, map[string]any{"id": "record-id", "type": "CNAME", "name": "mcp.example.com"})
		default:
			http.Error(w, "unexpected "+request.Method+" "+request.URL.RequestURI(), http.StatusNotFound)
		}
	}))
	defer server.Close()

	result, err := Provision(context.Background(), Config{
		APIToken: "api-token", Hostname: "mcp.example.com", TunnelName: "mcp-example",
		OriginURL: "http://127.0.0.1:8765", BaseURL: server.URL, HTTPClient: server.Client(),
	})
	if err != nil {
		t.Fatal(err)
	}
	if result.AccountID != accountID || result.ZoneID != zoneID || result.TunnelID != tunnelID ||
		result.TunnelToken != "tunnel-token" || !result.TunnelCreated || result.DNSAction != "created" {
		t.Fatalf("unexpected result: %+v", result)
	}
	joined := strings.Join(calls, "\n")
	if !strings.Contains(joined, "name=mcp.example.com") || !strings.Contains(joined, "name=example.com") {
		t.Fatalf("zone discovery calls missing:\n%s", joined)
	}
}

func TestProvisionReusesAndUpdates(t *testing.T) {
	const (
		accountID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
		zoneID    = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
		tunnelID  = "11111111-2222-3333-4444-555555555555"
	)
	createdTunnel := false
	createdDNS := false
	patchedDNS := false
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, request *http.Request) {
		switch {
		case request.Method == http.MethodGet && request.URL.Path == "/accounts/"+accountID+"/cfd_tunnel":
			writeAPI(w, []any{map[string]any{"id": tunnelID, "name": "existing", "config_src": "cloudflare"}})
		case request.Method == http.MethodPost && request.URL.Path == "/accounts/"+accountID+"/cfd_tunnel":
			createdTunnel = true
			writeAPI(w, map[string]any{})
		case request.Method == http.MethodPut && strings.HasSuffix(request.URL.Path, "/configurations"):
			writeAPI(w, map[string]any{})
		case request.Method == http.MethodGet && strings.HasSuffix(request.URL.Path, "/token"):
			writeAPI(w, "existing-token")
		case request.Method == http.MethodGet && request.URL.Path == "/zones/"+zoneID+"/dns_records":
			writeAPI(w, []any{map[string]any{
				"id": "record-id", "type": "CNAME", "name": "mcp.example.com",
				"content": "old.cfargotunnel.com", "proxied": false,
			}})
		case request.Method == http.MethodPost && request.URL.Path == "/zones/"+zoneID+"/dns_records":
			createdDNS = true
			writeAPI(w, map[string]any{})
		case request.Method == http.MethodPatch && request.URL.Path == "/zones/"+zoneID+"/dns_records/record-id":
			patchedDNS = true
			assertJSONField(t, request, "proxied", true)
			writeAPI(w, map[string]any{"id": "record-id"})
		default:
			http.Error(w, "unexpected "+request.Method+" "+request.URL.RequestURI(), http.StatusNotFound)
		}
	}))
	defer server.Close()

	result, err := Provision(context.Background(), Config{
		APIToken: "api-token", AccountID: accountID, ZoneID: zoneID,
		Hostname: "mcp.example.com", TunnelName: "existing", OriginURL: "http://127.0.0.1:8765",
		BaseURL: server.URL, HTTPClient: server.Client(),
	})
	if err != nil {
		t.Fatal(err)
	}
	if result.TunnelCreated || result.DNSAction != "updated" || createdTunnel || createdDNS || !patchedDNS {
		t.Fatalf("idempotency failed: result=%+v createTunnel=%v createDNS=%v patchDNS=%v", result, createdTunnel, createdDNS, patchedDNS)
	}
}

func TestProvisionRefusesConflictingDNS(t *testing.T) {
	const (
		accountID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
		zoneID    = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
		tunnelID  = "11111111-2222-3333-4444-555555555555"
	)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, request *http.Request) {
		switch {
		case request.Method == http.MethodGet && strings.HasSuffix(request.URL.Path, "/cfd_tunnel"):
			writeAPI(w, []any{map[string]any{"id": tunnelID, "name": "existing", "config_src": "cloudflare"}})
		case request.Method == http.MethodPut:
			writeAPI(w, map[string]any{})
		case request.Method == http.MethodGet && strings.HasSuffix(request.URL.Path, "/token"):
			writeAPI(w, "token")
		case request.Method == http.MethodGet && strings.HasSuffix(request.URL.Path, "/dns_records"):
			writeAPI(w, []any{map[string]any{"id": "record-id", "type": "A", "name": "mcp.example.com"}})
		default:
			http.Error(w, "unexpected", http.StatusNotFound)
		}
	}))
	defer server.Close()

	_, err := Provision(context.Background(), Config{
		APIToken: "api-token", AccountID: accountID, ZoneID: zoneID,
		Hostname: "mcp.example.com", TunnelName: "existing", OriginURL: "http://127.0.0.1:8765",
		BaseURL: server.URL, HTTPClient: server.Client(),
	})
	if err == nil || !strings.Contains(err.Error(), "refusing to replace") {
		t.Fatalf("error = %v", err)
	}
}

func writeAPI(w http.ResponseWriter, result any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"success": true, "errors": []any{}, "messages": []any{}, "result": result})
}

func decodeRequest(t *testing.T, request *http.Request, target any) {
	t.Helper()
	if err := json.NewDecoder(request.Body).Decode(target); err != nil {
		t.Fatal(err)
	}
}

func assertJSONField(t *testing.T, request *http.Request, key string, expected any) {
	t.Helper()
	var body map[string]any
	decodeRequest(t, request, &body)
	if fmt.Sprint(body[key]) != fmt.Sprint(expected) {
		t.Fatalf("%s = %#v, want %#v", key, body[key], expected)
	}
}
