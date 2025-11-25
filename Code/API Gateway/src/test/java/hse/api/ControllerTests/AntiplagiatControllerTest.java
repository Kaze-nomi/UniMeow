package hse.api.ControllerTests;

import hse.api.controllers.AntiplagiatController;
import hse.api.grpc.*;
import hse.api.grpc.FileAnalysisServiceGrpc.FileAnalysisServiceBlockingStub;
import hse.api.grpc.FileStorageServiceGrpc.FileStorageServiceBlockingStub;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@SpringBootTest
@AutoConfigureMockMvc
public class AntiplagiatControllerTest {

    @Mock
    private FileStorageServiceBlockingStub fileStorageClient;

    @Mock
    private FileAnalysisServiceBlockingStub fileAnalysisClient;

    @InjectMocks
    private AntiplagiatController controller;

    @Test
    void uploadFile_Success() throws Exception {

        when(fileStorageClient.uploadFile(any(UploadFileRequest.class)))
                .thenReturn(UploadFileResponse.newBuilder().setFileId("123").build());

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.txt",
                "text/plain",
                "content".getBytes());

        ResponseEntity<String> response = controller.uploadFile(file);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("123", response.getBody());
    }

    @Test
    void uploadFile_InvalidFileType() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "image.png",
                "image/png",
                "content".getBytes());

        ResponseEntity<String> response = controller.uploadFile(file);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().contains("Разрешены только .txt файлы"));
    }

    @Test
    void uploadFile_StorageUnavailable() {
        when(fileStorageClient.uploadFile(any()))
                .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.txt",
                "text/plain",
                "content".getBytes());

        ResponseEntity<String> response = controller.uploadFile(file);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertTrue(response.getBody().contains("Сервис storage сейчас спит"));
    }

    @Test
    void getFileContent_Success() {
        String testContent = "Hello World";
        when(fileStorageClient.getFile(any()))
                .thenReturn(GetFileResponse.newBuilder()
                        .setFileContent(com.google.protobuf.ByteString.copyFromUtf8(testContent))
                        .build());

        ResponseEntity<String> response = controller.getFileContent("123");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains(testContent));
    }

    @Test
    void getWordCloud_Success() {
        byte[] testImage = new byte[] { 0x12, 0x34, 0x56 };
        when(fileAnalysisClient.getWordCloud(any()))
                .thenReturn(WordCloudResponse.newBuilder()
                        .setWordCloud(com.google.protobuf.ByteString.copyFrom(testImage))
                        .build());

        ResponseEntity<?> response = controller.getWordCloudImage("123");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.IMAGE_PNG, response.getHeaders().getContentType());
        assertArrayEquals(testImage, (byte[]) response.getBody());

    }

    @Test
    void analyzeFile_InternalError() {
        when(fileAnalysisClient.analyzeFile(any()))
                .thenThrow(new StatusRuntimeException(Status.INTERNAL));

        ResponseEntity<String> response = controller.getFileAnalysis("123");

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertTrue(response.getBody().contains("Ошибка проведения анализа"));
    }

    @Test
    void analyzeFile_Unavailable() {
        when(fileAnalysisClient.analyzeFile(any()))
                .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));

        ResponseEntity<String> response = controller.getFileAnalysis("123");

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertTrue(response.getBody().contains("Сервис analysis сейчас спит"));
    }

    @Test
    void analyzeFile_NotFound() {
        when(fileAnalysisClient.analyzeFile(any()))
                .thenThrow(new StatusRuntimeException(Status.NOT_FOUND));

        ResponseEntity<String> response = controller.getFileAnalysis("123");

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertTrue(response.getBody().contains("Ошибка"));
    }

    @Test
    void analyzeFile_Success() {
        String testContent = "Hello World";
        when(fileAnalysisClient.analyzeFile(any()))
                .thenReturn(AnalyzeFileResponse.newBuilder()
                        .setAnalysisResult(testContent)
                        .build());

        ResponseEntity<String> response = controller.getFileAnalysis("123");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains(testContent));
    }

    @Test
    void uploadFile_IOException() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "test.txt",
            "text/plain",
            "content".getBytes()
        ) {
            @Override
            public byte[] getBytes() throws IOException {
                throw new IOException("Simulated IO error");
            }
        };

        ResponseEntity<String> response = controller.uploadFile(file);
        
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertTrue(response.getBody().contains("Ошибка загрузки файла"));
    }

    @Test
    void uploadFile_GeneralGrpcError() {
        when(fileStorageClient.uploadFile(any()))
            .thenThrow(new StatusRuntimeException(Status.UNKNOWN));

        MockMultipartFile file = new MockMultipartFile(
            "file",
            "test.txt",
            "text/plain",
            "content".getBytes()
        );

        ResponseEntity<String> response = controller.uploadFile(file);
        
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertTrue(response.getBody().contains("Ошибка при общении с storage"));
    }

    @Test
    void getFileContent_GrpcError() {
        when(fileStorageClient.getFile(any()))
            .thenThrow(new StatusRuntimeException(Status.PERMISSION_DENIED));

        ResponseEntity<String> response = controller.getFileContent("123");
        
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertTrue(response.getBody().contains("Ошибка получения файла"));
    }

    @Test
    void getFileContent_NotFound() {
        when(fileStorageClient.getFile(any()))
            .thenThrow(new StatusRuntimeException(Status.NOT_FOUND));

        ResponseEntity<String> response = controller.getFileContent("123");
        
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertTrue(response.getBody().contains("Ошибка получения файла"));
    }

    @Test
    void getFileContent_Unavailable() {
        when(fileStorageClient.getFile(any()))
            .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));

        ResponseEntity<String> response = controller.getFileContent("123");
        
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertTrue(response.getBody().contains("Сервис storage сейчас спит"));
    }

    @Test
    void getWordCloudImage_GrpcError() {
        when(fileAnalysisClient.getWordCloud(any()))
            .thenThrow(new StatusRuntimeException(Status.INTERNAL));

        ResponseEntity<?> response = controller.getWordCloudImage("123");
        
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertTrue(new String((byte[]) response.getBody(), StandardCharsets.UTF_8)
            .contains("Ошибка получения облака слов"));
    }

    @Test
    void getWordCloudImage_ServiceUnavailable() {
        when(fileAnalysisClient.getWordCloud(any()))
            .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));

        ResponseEntity<?> response = controller.getWordCloudImage("123");
        
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertTrue(new String((byte[]) response.getBody(), StandardCharsets.UTF_8)
            .contains("Сервис analysis сейчас спит"));
    }
}