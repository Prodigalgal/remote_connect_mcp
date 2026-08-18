package auth

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestBearer(t *testing.T) {
	handler := Bearer("secret", http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	for _, test := range []struct {
		name   string
		header string
		status int
	}{
		{"missing", "", http.StatusUnauthorized},
		{"wrong", "Bearer nope", http.StatusUnauthorized},
		{"basic", "Basic secret", http.StatusUnauthorized},
		{"valid", "Bearer secret", http.StatusNoContent},
		{"case insensitive scheme", "bearer secret", http.StatusNoContent},
	} {
		t.Run(test.name, func(t *testing.T) {
			request := httptest.NewRequest(http.MethodPost, "/mcp", nil)
			request.Header.Set("Authorization", test.header)
			response := httptest.NewRecorder()
			handler.ServeHTTP(response, request)
			if response.Code != test.status {
				t.Fatalf("status = %d, want %d", response.Code, test.status)
			}
		})
	}
}
