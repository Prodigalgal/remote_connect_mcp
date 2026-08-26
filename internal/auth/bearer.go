package auth

import (
	"crypto/subtle"
	"encoding/json"
	"net/http"
	"strings"
)

func Bearer(token string, next http.Handler) http.Handler {
	expected := []byte(token)
	return BearerValidator(func(actual string) bool {
		return len(actual) == len(expected) && subtle.ConstantTimeCompare([]byte(actual), expected) == 1
	}, next)
}

func BearerValidator(validate func(string) bool, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		header := r.Header.Get("Authorization")
		parts := strings.SplitN(header, " ", 2)
		valid := len(parts) == 2 && strings.EqualFold(parts[0], "Bearer") && validate != nil && validate(parts[1])
		if !valid {
			w.Header().Set("WWW-Authenticate", `Bearer realm="remote_connect_mcp"`)
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusUnauthorized)
			_ = json.NewEncoder(w).Encode(map[string]string{"error": "unauthorized"})
			return
		}
		next.ServeHTTP(w, r)
	})
}
