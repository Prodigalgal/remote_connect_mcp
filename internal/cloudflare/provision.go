package cloudflare

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"time"
)

const defaultBaseURL = "https://api.cloudflare.com/client/v4"

var (
	hostnameLabel = regexp.MustCompile(`^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$`)
	identifier    = regexp.MustCompile(`^[A-Za-z0-9_-]+$`)
)

type Config struct {
	APIToken   string
	AccountID  string
	ZoneID     string
	ZoneName   string
	Hostname   string
	TunnelName string
	OriginURL  string
	BaseURL    string
	HTTPClient *http.Client
}

type Result struct {
	AccountID     string
	ZoneID        string
	ZoneName      string
	Hostname      string
	TunnelID      string
	TunnelName    string
	TunnelToken   string
	TunnelCreated bool
	DNSAction     string
}

type client struct {
	baseURL string
	token   string
	http    *http.Client
}

type apiEnvelope struct {
	Success  bool            `json:"success"`
	Errors   []apiMessage    `json:"errors"`
	Messages []apiMessage    `json:"messages"`
	Result   json.RawMessage `json:"result"`
}

type apiMessage struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
}

type zone struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	Account struct {
		ID   string `json:"id"`
		Name string `json:"name"`
	} `json:"account"`
}

type tunnel struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	ConfigSrc string `json:"config_src"`
}

type dnsRecord struct {
	ID      string `json:"id"`
	Type    string `json:"type"`
	Name    string `json:"name"`
	Content string `json:"content"`
	Proxied bool   `json:"proxied"`
}

func Provision(ctx context.Context, config Config) (Result, error) {
	normalized, err := normalizeConfig(config)
	if err != nil {
		return Result{}, err
	}
	api := &client{baseURL: normalized.BaseURL, token: normalized.APIToken, http: normalized.HTTPClient}

	resolvedZone, err := api.resolveZone(ctx, normalized)
	if err != nil {
		return Result{}, err
	}
	accountID := normalized.AccountID
	if accountID == "" {
		accountID = resolvedZone.Account.ID
	}
	if accountID == "" {
		return Result{}, errors.New("Cloudflare account ID was not supplied and could not be discovered from the zone")
	}
	if resolvedZone.Account.ID != "" && normalized.AccountID != "" && resolvedZone.Account.ID != normalized.AccountID {
		return Result{}, fmt.Errorf("zone %s belongs to account %s, not configured account %s", resolvedZone.Name, resolvedZone.Account.ID, normalized.AccountID)
	}

	foundTunnel, created, err := api.ensureTunnel(ctx, accountID, normalized.TunnelName)
	if err != nil {
		return Result{}, err
	}
	if err := api.configureTunnel(ctx, accountID, foundTunnel.ID, normalized.Hostname, normalized.OriginURL); err != nil {
		return Result{}, err
	}
	tunnelToken, err := api.getTunnelToken(ctx, accountID, foundTunnel.ID)
	if err != nil {
		return Result{}, err
	}
	dnsAction, err := api.ensureDNS(ctx, resolvedZone.ID, normalized.Hostname, foundTunnel.ID)
	if err != nil {
		return Result{}, err
	}

	return Result{
		AccountID: accountID, ZoneID: resolvedZone.ID, ZoneName: resolvedZone.Name,
		Hostname: normalized.Hostname, TunnelID: foundTunnel.ID, TunnelName: foundTunnel.Name,
		TunnelToken: tunnelToken, TunnelCreated: created, DNSAction: dnsAction,
	}, nil
}

func normalizeConfig(config Config) (Config, error) {
	config.APIToken = strings.TrimSpace(config.APIToken)
	if config.APIToken == "" {
		return Config{}, errors.New("Cloudflare API token is required")
	}
	var err error
	config.Hostname, err = normalizeHostname(config.Hostname)
	if err != nil {
		return Config{}, fmt.Errorf("hostname: %w", err)
	}
	if config.ZoneName != "" {
		config.ZoneName, err = normalizeHostname(config.ZoneName)
		if err != nil {
			return Config{}, fmt.Errorf("zone name: %w", err)
		}
		if config.Hostname != config.ZoneName && !strings.HasSuffix(config.Hostname, "."+config.ZoneName) {
			return Config{}, fmt.Errorf("hostname %s is not inside zone %s", config.Hostname, config.ZoneName)
		}
	}
	config.AccountID = strings.TrimSpace(config.AccountID)
	config.ZoneID = strings.TrimSpace(config.ZoneID)
	if config.AccountID != "" && !identifier.MatchString(config.AccountID) {
		return Config{}, errors.New("account ID contains invalid characters")
	}
	if config.ZoneID != "" && !identifier.MatchString(config.ZoneID) {
		return Config{}, errors.New("zone ID contains invalid characters")
	}
	config.TunnelName = strings.TrimSpace(config.TunnelName)
	if config.TunnelName == "" {
		config.TunnelName = strings.ReplaceAll(config.Hostname, ".", "-")
	}
	if len(config.TunnelName) > 100 || strings.ContainsAny(config.TunnelName, "\r\n") {
		return Config{}, errors.New("tunnel name is invalid or longer than 100 characters")
	}
	origin, err := url.Parse(config.OriginURL)
	if err != nil || origin.Host == "" || origin.User != nil || origin.Scheme != "http" && origin.Scheme != "https" {
		return Config{}, errors.New("origin URL must be an HTTP or HTTPS URL without user information")
	}
	config.OriginURL = strings.TrimRight(origin.String(), "/")
	if config.BaseURL == "" {
		config.BaseURL = defaultBaseURL
	}
	config.BaseURL = strings.TrimRight(config.BaseURL, "/")
	if config.HTTPClient == nil {
		config.HTTPClient = &http.Client{Timeout: 30 * time.Second}
	}
	return config, nil
}

func normalizeHostname(hostname string) (string, error) {
	hostname = strings.ToLower(strings.TrimSuffix(strings.TrimSpace(hostname), "."))
	if hostname == "" || len(hostname) > 253 || strings.ContainsAny(hostname, "/:@") {
		return "", errors.New("expected a DNS hostname without scheme, port, or path")
	}
	labels := strings.Split(hostname, ".")
	if len(labels) < 2 {
		return "", errors.New("hostname must contain at least two labels")
	}
	for _, label := range labels {
		if !hostnameLabel.MatchString(label) {
			return "", fmt.Errorf("invalid label %q", label)
		}
	}
	return hostname, nil
}

func (c *client) resolveZone(ctx context.Context, config Config) (zone, error) {
	if config.ZoneID != "" {
		if config.AccountID != "" {
			return zone{ID: config.ZoneID, Name: config.ZoneName}, nil
		}
		var result zone
		if err := c.do(ctx, http.MethodGet, "/zones/"+config.ZoneID, nil, nil, &result); err != nil {
			return zone{}, fmt.Errorf("discover account from zone ID: %w", err)
		}
		return result, nil
	}

	candidates := []string{}
	if config.ZoneName != "" {
		candidates = append(candidates, config.ZoneName)
	} else {
		labels := strings.Split(config.Hostname, ".")
		for index := 0; index < len(labels)-1; index++ {
			candidates = append(candidates, strings.Join(labels[index:], "."))
		}
	}
	for _, candidate := range candidates {
		query := url.Values{"name": {candidate}, "status": {"active"}, "per_page": {"50"}}
		var zones []zone
		if err := c.do(ctx, http.MethodGet, "/zones", query, nil, &zones); err != nil {
			return zone{}, fmt.Errorf("discover zone %s: %w", candidate, err)
		}
		for _, found := range zones {
			if strings.EqualFold(found.Name, candidate) {
				return found, nil
			}
		}
	}
	return zone{}, fmt.Errorf("no active Cloudflare zone matched %s; grant Zone Read or set CLOUDFLARE_ZONE_ID and CLOUDFLARE_ACCOUNT_ID", config.Hostname)
}

func (c *client) ensureTunnel(ctx context.Context, accountID, name string) (tunnel, bool, error) {
	query := url.Values{"name": {name}, "is_deleted": {"false"}, "per_page": {"100"}}
	var tunnels []tunnel
	if err := c.do(ctx, http.MethodGet, "/accounts/"+accountID+"/cfd_tunnel", query, nil, &tunnels); err != nil {
		return tunnel{}, false, fmt.Errorf("list Cloudflare tunnels: %w", err)
	}
	var exact []tunnel
	for _, candidate := range tunnels {
		if candidate.Name == name {
			exact = append(exact, candidate)
		}
	}
	if len(exact) > 1 {
		return tunnel{}, false, fmt.Errorf("multiple active tunnels are named %q", name)
	}
	if len(exact) == 1 {
		if exact[0].ConfigSrc != "" && exact[0].ConfigSrc != "cloudflare" {
			return tunnel{}, false, fmt.Errorf("tunnel %q is locally managed; choose a dedicated name for auto-provisioning", name)
		}
		return exact[0], false, nil
	}
	body := map[string]any{"name": name, "config_src": "cloudflare"}
	var created tunnel
	if err := c.do(ctx, http.MethodPost, "/accounts/"+accountID+"/cfd_tunnel", nil, body, &created); err != nil {
		return tunnel{}, false, fmt.Errorf("create Cloudflare tunnel: %w", err)
	}
	if created.ID == "" {
		return tunnel{}, false, errors.New("Cloudflare created a tunnel without returning its ID")
	}
	return created, true, nil
}

func (c *client) configureTunnel(ctx context.Context, accountID, tunnelID, hostname, originURL string) error {
	body := map[string]any{"config": map[string]any{"ingress": []map[string]any{
		{"hostname": hostname, "service": originURL},
		{"service": "http_status:404"},
	}}}
	var result any
	if err := c.do(ctx, http.MethodPut, "/accounts/"+accountID+"/cfd_tunnel/"+tunnelID+"/configurations", nil, body, &result); err != nil {
		return fmt.Errorf("configure Cloudflare tunnel: %w", err)
	}
	return nil
}

func (c *client) getTunnelToken(ctx context.Context, accountID, tunnelID string) (string, error) {
	var token string
	if err := c.do(ctx, http.MethodGet, "/accounts/"+accountID+"/cfd_tunnel/"+tunnelID+"/token", nil, nil, &token); err != nil {
		return "", fmt.Errorf("get Cloudflare tunnel token: %w", err)
	}
	if strings.TrimSpace(token) == "" {
		return "", errors.New("Cloudflare returned an empty tunnel token")
	}
	return token, nil
}

func (c *client) ensureDNS(ctx context.Context, zoneID, hostname, tunnelID string) (string, error) {
	query := url.Values{"name.exact": {hostname}, "match": {"all"}, "per_page": {"100"}}
	var records []dnsRecord
	if err := c.do(ctx, http.MethodGet, "/zones/"+zoneID+"/dns_records", query, nil, &records); err != nil {
		return "", fmt.Errorf("list Cloudflare DNS records: %w", err)
	}
	if len(records) > 1 {
		return "", fmt.Errorf("multiple DNS records already use hostname %s; resolve the conflict manually", hostname)
	}
	body := map[string]any{
		"type": "CNAME", "name": hostname, "content": tunnelID + ".cfargotunnel.com",
		"proxied": true, "ttl": 1,
	}
	if len(records) == 0 {
		var created dnsRecord
		if err := c.do(ctx, http.MethodPost, "/zones/"+zoneID+"/dns_records", nil, body, &created); err != nil {
			return "", fmt.Errorf("create Cloudflare DNS record: %w", err)
		}
		return "created", nil
	}
	if records[0].Type != "CNAME" {
		return "", fmt.Errorf("hostname %s already has a %s record; refusing to replace it automatically", hostname, records[0].Type)
	}
	var updated dnsRecord
	if err := c.do(ctx, http.MethodPatch, "/zones/"+zoneID+"/dns_records/"+records[0].ID, nil, body, &updated); err != nil {
		return "", fmt.Errorf("update Cloudflare DNS record: %w", err)
	}
	return "updated", nil
}

func (c *client) do(ctx context.Context, method, path string, query url.Values, body, result any) error {
	endpoint := c.baseURL + path
	if len(query) > 0 {
		endpoint += "?" + query.Encode()
	}
	var reader io.Reader
	if body != nil {
		data, err := json.Marshal(body)
		if err != nil {
			return err
		}
		reader = bytes.NewReader(data)
	}
	request, err := http.NewRequestWithContext(ctx, method, endpoint, reader)
	if err != nil {
		return err
	}
	request.Header.Set("Authorization", "Bearer "+c.token)
	request.Header.Set("Accept", "application/json")
	request.Header.Set("User-Agent", "remote-mcp/0.1")
	if body != nil {
		request.Header.Set("Content-Type", "application/json")
	}
	response, err := c.http.Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	data, err := io.ReadAll(io.LimitReader(response.Body, 4*1024*1024))
	if err != nil {
		return err
	}
	var envelope apiEnvelope
	if err := json.Unmarshal(data, &envelope); err != nil {
		return fmt.Errorf("Cloudflare API returned HTTP %s with invalid JSON", response.Status)
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 || !envelope.Success {
		return apiError(response.Status, envelope.Errors, envelope.Messages)
	}
	if result == nil || len(envelope.Result) == 0 || string(envelope.Result) == "null" {
		return nil
	}
	if err := json.Unmarshal(envelope.Result, result); err != nil {
		return fmt.Errorf("decode Cloudflare API result: %w", err)
	}
	return nil
}

func apiError(status string, errorsList, messages []apiMessage) error {
	items := append(append([]apiMessage{}, errorsList...), messages...)
	parts := make([]string, 0, len(items))
	for _, item := range items {
		if item.Message == "" {
			continue
		}
		if item.Code != 0 {
			parts = append(parts, fmt.Sprintf("%d: %s", item.Code, item.Message))
		} else {
			parts = append(parts, item.Message)
		}
	}
	if len(parts) == 0 {
		return fmt.Errorf("Cloudflare API request failed: HTTP %s", status)
	}
	return fmt.Errorf("Cloudflare API request failed: HTTP %s: %s", status, strings.Join(parts, "; "))
}
