package center

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestConsoleEnrollmentInstallerActions(t *testing.T) {
	request := httptest.NewRequest(http.MethodGet, "/console/", nil)
	response := httptest.NewRecorder()

	serveConsole(response, request)

	if response.Code != http.StatusOK {
		t.Fatalf("console status = %d", response.Code)
	}
	body := response.Body.String()
	for _, expected := range []string{
		`id="enrollmentResult" class="enrollment-result hidden"`,
		`id="copyEnrollmentToken"`,
		`id="copyEnrollmentConfig"`,
		`id="downloadEnrollmentScript"`,
		`Token 与平台无关`,
		`white-space:pre-wrap`,
		`sha256sum -c`,
		`Get-FileHash -Algorithm SHA256`,
	} {
		if !strings.Contains(body, expected) {
			t.Errorf("console is missing %q", expected)
		}
	}
}
