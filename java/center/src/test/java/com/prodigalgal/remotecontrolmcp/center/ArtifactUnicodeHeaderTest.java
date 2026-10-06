package com.prodigalgal.remotecontrolmcp.center;

import org.junit.jupiter.api.Test;
import org.springframework.http.ContentDisposition;
import java.io.ByteArrayInputStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ArtifactUnicodeHeaderTest {
    @Test void downloadsUseAsciiHeaderEncodingAndPreserveUnicodeFilename() throws Exception {
        var transfers = mock(ArtifactTransferService.class);
        when(transfers.openPublic("test-url", 0L)).thenReturn(new ArtifactTransferService.PublicArtifact(
                "artifact-test", "大乐透 中文报告.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 1, "digest", "delivered", new ByteArrayInputStream(new byte[]{1})));
        var response = new ArtifactController(transfers).content("artifact-test", null, "", "", "", "download", "", "test-url", 0, null);
        var header = response.getHeaders().getFirst("Content-Disposition");
        assertNotNull(header); assertTrue(header.chars().allMatch(value -> value < 128));
        assertEquals("大乐透 中文报告.xlsx", ContentDisposition.parse(header).getFilename());
        assertTrue(header.contains("filename*="));
    }
}
