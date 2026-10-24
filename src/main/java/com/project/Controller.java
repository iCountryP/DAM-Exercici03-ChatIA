package com.project;

import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.event.ActionEvent;
import javafx.application.Platform;
import javafx.fxml.Initializable;
import javafx.stage.FileChooser;

import java.net.URL;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.File;
import java.util.Base64;
import java.util.ResourceBundle;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONArray;
import org.json.JSONObject;

import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

public class Controller implements Initializable {

    // Model configuration (Agents IETI)
    private static final String MODEL_NAME = "Qwen38";
    private static final String MODEL_URL = "https://agents.ieti.site/v1/chat/completions";
    private static final String MODEL_KEY = ""; // Replace with your
                                                                                                   // actual API key

    // Model configuration (Qwen3-VL)
    // private static final String MODEL_NAME = "qwen3-vl";
    // private static final String MODEL_URL =
    // "http://localhost:8080/v1/chat/completions";

    @FXML
    private Button buttonBreak, buttonPicture, btnSend;
    // @FXML esta era la antigua que usaba albert. ya no sirve
    // private Text textInfo;

    @FXML
    private TextField txtMessage;
    @FXML
    private VBox chatContainer;
    private Text respuestaActual;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    private CompletableFuture<HttpResponse<InputStream>> streamRequest;
    private CompletableFuture<HttpResponse<String>> completeRequest;

    private final AtomicBoolean isCancelled = new AtomicBoolean(false);

    private InputStream currentInputStream;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private Future<?> streamReadingTask;

    private volatile boolean isFirst = false;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        setButtonsIdle();
    }

    // --- UI actions ---
    private Text agregarMensaje(String autor, String mensaje) {
        // Creamos un texto nuevo con el nombre y el mensaje
        Text texto = new Text(autor + "\n" + mensaje);

        // Permitimos que el texto salte de línea
        // Cogemos el ancho del chat y restamos 30 píxeles para dejar margen
        texto.wrappingWidthProperty().bind(
                chatContainer.widthProperty().subtract(30));

        // Añadimos el mensaje al historial del chat
        chatContainer.getChildren().add(texto);

        // Devolvemos el texto por si necesitamos modificarlo después
        return texto;
    }

    private void agregarImagen(File file) {
        // Cargamos la imagen seleccionada
        Image imagen = new Image(file.toURI().toString());

        // Creamos el componente que mostrará la imagen
        ImageView miniatura = new ImageView(imagen);

        // Limitamos el tamaño de la miniatura
        miniatura.setFitWidth(150);
        miniatura.setFitHeight(150);

        // Mantenemos las proporciones originales
        miniatura.setPreserveRatio(true);

        // Mostramos la imagen dentro del historial
        chatContainer.getChildren().add(miniatura);
    }

    private void actualizarRespuesta(String mensaje) {
        // Actualizamos el mensaje de la IA en el historial
        if (respuestaActual != null) {
            respuestaActual.setText("Xat IETI\n" + mensaje);
        }
    }

    @FXML
    private void sendMessage(ActionEvent event) {
        // System.out.println("pulsado boton enviar!!!");

        // Cogemos el mensaje escrito y quitamos los espacios sobrantes
        String mensaje = txtMessage.getText().trim();

        // Si no se ha escrito nada, no hacemos nada
        if (mensaje.isEmpty()) {
            return;
        }

        // Limpiamos el campo de texto después de enviar
        txtMessage.clear();

        // Mostramos nuestro mensaje en el historial
        agregarMensaje("You", mensaje);

        // Creamos el espacio donde la IA escribirá su respuesta
        respuestaActual = agregarMensaje("Xat IETI", "");

        // Desactivamos los botones mientras la IA responde
        setButtonsRunning();

        // Reiniciamos el estado de cancelación
        isCancelled.set(false);

        // Enviamos el mensaje a la IA con streaming activado
        executeTextRequest(mensaje, true);
    }

    @FXML
    private void callPicture(ActionEvent event) {
        setButtonsRunning();
        isCancelled.set(false);

        FileChooser fc = new FileChooser();
        fc.setTitle("Choose an image");

        fc.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(
                        "Images",
                        "*.png", "*.jpg", "*.jpeg", "*.webp", "*.bmp", "*.gif"));

        File initialDir = new File(System.getProperty("user.dir"));

        if (initialDir.exists() && initialDir.isDirectory()) {
            fc.setInitialDirectory(initialDir);
        }

        File file = fc.showOpenDialog(buttonPicture.getScene().getWindow());

        if (file == null) {
            actualizarRespuesta("No file selected.");
            setButtonsIdle();
            return;
        }

        // Mostramos la imagen seleccionada en el historial
        agregarMensaje("You", "Imagen adjuntada");
        agregarImagen(file);

        // Creamos un mensaje nuevo para la respuesta de la IA
        respuestaActual = agregarMensaje("Xat IETI", "");

        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                byte[] bytes = Files.readAllBytes(file.toPath());
                return Base64.getEncoder().encodeToString(bytes);
            }
        };

        task.setOnSucceeded(e -> {
            String base64Image = task.getValue();
            String mimeType = getMimeType(file);

            // Cogemos la pregunta que haya escrito el usuario
            String pregunta = txtMessage.getText().trim();

            // Si no ha escrito nada, usamos una pregunta por defecto
            if (pregunta.isEmpty()) {
                pregunta = "Describe what's in this picture";
            }

            // Limpiamos el campo después de utilizar la pregunta
            txtMessage.clear();

            executeImageRequest(
                    pregunta,
                    base64Image,
                    mimeType);
        });

        task.setOnFailed(e -> {
            task.getException().printStackTrace();
            actualizarRespuesta("Error reading image.");
            setButtonsIdle();
        });

        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
    }

    @FXML
    private void callBreak(ActionEvent event) {
        isCancelled.set(true);

        cancelStreamRequest();
        cancelCompleteRequest();

        actualizarRespuesta("Request cancelled.");
        setButtonsIdle();
    }

    // --- Text request ---

    private void executeTextRequest(String prompt, boolean stream) {
        JSONArray messages = new JSONArray().put(
                new JSONObject()
                        .put("role", "user")
                        .put("content", prompt));

        JSONObject body = new JSONObject()
                .put("model", MODEL_NAME)
                .put("messages", messages)
                .put("stream", stream)
                .put("reasoning_effort", "none");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(MODEL_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + MODEL_KEY)
                .POST(BodyPublishers.ofString(body.toString()))
                .build();

        if (stream) {
            // cambio esto para que aparezca en el chat
            respuestaActual.setText("Xat IETI\nThinking...");
            isFirst = true;

            streamRequest = httpClient.sendAsync(
                    request,
                    HttpResponse.BodyHandlers.ofInputStream()).thenApply(response -> {
                        int code = response.statusCode();

                        if (code < 200 || code >= 300) {
                            try {
                                String bodyStr = new String(
                                        response.body().readAllBytes(),
                                        StandardCharsets.UTF_8);

                                String message = extractOpenAIError(bodyStr);

                                if (message == null || message.isBlank()) {
                                    message = "HTTP " + code + ": " + bodyStr;
                                }

                                final String toShow = message;

                                Platform.runLater(() -> {
                                    actualizarRespuesta(toShow);
                                    setButtonsIdle();
                                });

                            } catch (Exception e) {
                                e.printStackTrace();

                                Platform.runLater(() -> {
                                    actualizarRespuesta("HTTP error: " + code);
                                    setButtonsIdle();
                                });
                            }

                            return response;
                        }

                        currentInputStream = response.body();
                        streamReadingTask = executorService.submit(this::handleStreamResponse);

                        return response;

                    }).exceptionally(e -> {
                        if (!isCancelled.get()) {
                            e.printStackTrace();

                            Platform.runLater(() -> {
                                actualizarRespuesta("Request failed: " + getErrorMessage(e));
                                setButtonsIdle();
                            });
                        }

                        return null;
                    });

        } else {
            actualizarRespuesta("Wait complete ...");

            completeRequest = httpClient.sendAsync(
                    request,
                    HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
                        int code = response.statusCode();
                        String bodyStr = response.body();
                        String message;

                        if (code >= 200 && code < 300) {
                            message = extractOpenAIResponse(bodyStr);
                        } else {
                            message = extractOpenAIError(bodyStr);

                            if (message == null || message.isBlank()) {
                                message = "HTTP " + code + ": " + bodyStr;
                            }
                        }

                        final String toShow = message;

                        Platform.runLater(() -> {
                            actualizarRespuesta(toShow);
                            setButtonsIdle();
                        });

                        return response;

                    }).exceptionally(e -> {
                        if (!isCancelled.get()) {
                            e.printStackTrace();

                            Platform.runLater(() -> {
                                actualizarRespuesta("Request failed: " + getErrorMessage(e));
                                setButtonsIdle();
                            });
                        }

                        return null;
                    });
        }
    }

    // --- Image request ---

    private void executeImageRequest(String prompt, String base64Image, String mimeType) {
        actualizarRespuesta("Thinking...");

        JSONArray content = new JSONArray()
                .put(
                        new JSONObject()
                                .put("type", "text")
                                .put("text", prompt))
                .put(
                        new JSONObject()
                                .put("type", "image_url")
                                .put(
                                        "image_url",
                                        new JSONObject().put(
                                                "url",
                                                "data:" + mimeType + ";base64," + base64Image)));

        JSONArray messages = new JSONArray().put(
                new JSONObject()
                        .put("role", "user")
                        .put("content", content));

        JSONObject body = new JSONObject()
                .put("model", MODEL_NAME)
                .put("messages", messages)
                .put("stream", false)
                .put("reasoning_effort", "none");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(MODEL_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + MODEL_KEY)
                .POST(BodyPublishers.ofString(body.toString()))
                .build();

        completeRequest = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            int code = response.statusCode();
            String bodyStr = response.body();
            String message;

            if (code >= 200 && code < 300) {
                message = extractOpenAIResponse(bodyStr);
            } else {
                message = extractOpenAIError(bodyStr);

                if (message == null || message.isBlank()) {
                    message = "HTTP " + code + ": " + bodyStr;
                }
            }

            final String toShow = message;

            Platform.runLater(() -> {
                actualizarRespuesta(toShow);
                setButtonsIdle();
            });

            return response;

        }).exceptionally(e -> {
            if (!isCancelled.get()) {
                e.printStackTrace();

                Platform.runLater(() -> {
                    actualizarRespuesta("Request failed: " + getErrorMessage(e));
                    setButtonsIdle();
                });
            }

            return null;
        });
    }

    // --- Stream reader ---

    private void handleStreamResponse() {
        try (
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(currentInputStream, StandardCharsets.UTF_8))) {
            String line;

            while ((line = reader.readLine()) != null) {
                if (isCancelled.get())
                    break;
                if (line.isBlank())
                    continue;
                if (!line.startsWith("data:"))
                    continue;

                String data = line.substring(5).trim();

                if (data.equals("[DONE]"))
                    break;

                JSONObject jsonResponse = new JSONObject(data);
                JSONArray choices = jsonResponse.optJSONArray("choices");

                if (choices == null || choices.isEmpty())
                    continue;

                JSONObject delta = choices
                        .getJSONObject(0)
                        .optJSONObject("delta");

                if (delta == null)
                    continue;

                String chunk = delta.optString("content", "");

                if (chunk.isEmpty())
                    continue;

                if (isFirst) {
                    // Mostramos el primer fragmento de la respuesta
                    Platform.runLater(() -> respuestaActual.setText("Xat IETI\n" + chunk));
                    isFirst = false;
                } else {
                    // Añadimos los siguientes fragmentos sin borrar los anteriores
                    Platform.runLater(() -> respuestaActual.setText(respuestaActual.getText() + chunk));
                }
            }

        } catch (Exception e) {
            if (!isCancelled.get()) {
                e.printStackTrace();

                Platform.runLater(() -> actualizarRespuesta(
                        "Error during streaming: " + getErrorMessage(e)));
            }

        } finally {
            try {
                if (currentInputStream != null)
                    currentInputStream.close();
            } catch (Exception ignore) {
                /* TODO: manage exception */}

            Platform.runLater(this::setButtonsIdle);
        }
    }

    // --- OpenAI response helpers ---

    private String extractOpenAIResponse(String bodyStr) {
        try {
            JSONObject response = new JSONObject(bodyStr);
            JSONArray choices = response.optJSONArray("choices");

            if (choices != null && !choices.isEmpty()) {
                JSONObject message = choices
                        .getJSONObject(0)
                        .optJSONObject("message");

                if (message != null) {
                    String content = message.optString("content", "");

                    if (!content.isBlank()) {
                        return content;
                    }
                }
            }

            String error = extractOpenAIError(bodyStr);

            if (error != null) {
                return error;
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return bodyStr != null && !bodyStr.isBlank()
                ? bodyStr
                : "(empty)";
    }

    private String extractOpenAIError(String bodyStr) {
        try {
            JSONObject response = new JSONObject(bodyStr);
            JSONObject error = response.optJSONObject("error");

            if (error != null) {
                return "Error: " + error.optString(
                        "message",
                        error.toString());
            }

        } catch (Exception ignore) {
            /* TODO: manage exception */}

        return null;
    }

    private String getErrorMessage(Throwable e) {
        Throwable cause = e;

        while (cause.getCause() != null) {
            cause = cause.getCause();
        }

        if (cause.getMessage() != null) {
            return cause.getMessage();
        }

        return cause.getClass().getSimpleName();
    }

    // --- Image MIME type ---

    private String getMimeType(File file) {
        try {
            String mimeType = Files.probeContentType(file.toPath());

            if (mimeType != null && mimeType.startsWith("image/")) {
                return mimeType;
            }

        } catch (Exception ignore) {
            /* TODO: manage exception */}

        String name = file.getName().toLowerCase();

        if (name.endsWith(".png"))
            return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg"))
            return "image/jpeg";
        if (name.endsWith(".webp"))
            return "image/webp";
        if (name.endsWith(".gif"))
            return "image/gif";
        if (name.endsWith(".bmp"))
            return "image/bmp";

        return "application/octet-stream";
    }

    // --- Cancel requests ---

    private void cancelStreamRequest() {
        if (streamRequest != null && !streamRequest.isDone()) {
            try {
                if (currentInputStream != null) {
                    System.out.println("Cancelling InputStream");
                    currentInputStream.close();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            System.out.println("Cancelling StreamRequest");

            if (streamReadingTask != null) {
                streamReadingTask.cancel(true);
            }

            streamRequest.cancel(true);
        }
    }

    private void cancelCompleteRequest() {
        if (completeRequest != null && !completeRequest.isDone()) {
            System.out.println("Cancelling CompleteRequest");
            completeRequest.cancel(true);
        }
    }

    // --- UI state ---

    private void setButtonsRunning() {
        buttonPicture.setDisable(true);
        buttonBreak.setDisable(false);
        btnSend.setDisable(true); // no permitimos enviar mientras la IA responde
    }

    private void setButtonsIdle() {
        buttonPicture.setDisable(false);
        buttonBreak.setDisable(true);
        btnSend.setDisable(false); // volvemos a permitir enviar mensajes

        streamRequest = null;
        completeRequest = null;
    }
}