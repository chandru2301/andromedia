package com.andromedia.cli.qa;

import com.andromedia.cli.progress.CliPhase;
import com.andromedia.cli.progress.CliProgressReporter;
import com.andromedia.llm.ChatCompletionService;
import com.andromedia.llm.ChatProgressListener;
import com.andromedia.llm.RagPrompt;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

@Service
public class ReportingChatCompletionService {

  private final ChatCompletionService delegate;

  public ReportingChatCompletionService(ChatCompletionService delegate) {
    this.delegate = delegate;
  }

  public void streamAnswer(
      RagPrompt prompt,
      Consumer<String> onToken,
      CliProgressReporter progress,
      String model,
      AtomicReference<Instant> firstTokenAt) {
    AtomicReference<Instant> modelWaitStart = new AtomicReference<>();
    ChatProgressListener listener =
        new ChatProgressListener() {
          @Override
          public void onRequestStarted(String modelId) {
            modelWaitStart.set(Instant.now());
            progress.startPhase(CliPhase.WAITING_FOR_MODEL);
            progress.updatePhase(CliPhase.WAITING_FOR_MODEL, modelId);
          }

          @Override
          public void onRetry(int attempt, int maxAttempts, long delayMs, int statusCode) {
            String detail = retryDetail(attempt, maxAttempts, delayMs, statusCode);
            progress.startPhase(CliPhase.RETRYING_RATE_LIMIT);
            progress.updatePhase(CliPhase.RETRYING_RATE_LIMIT, detail);
            progress.waitWithProgress(CliPhase.RETRYING_RATE_LIMIT, detail, delayMs);
            progress.completePhase(CliPhase.RETRYING_RATE_LIMIT, Duration.ofMillis(delayMs));
            progress.startPhase(CliPhase.WAITING_FOR_MODEL);
            progress.updatePhase(CliPhase.WAITING_FOR_MODEL, model);
          }

          @Override
          public boolean handlesRetryDelay() {
            return true;
          }

          @Override
          public void onFirstToken() {
            Instant waitStart = modelWaitStart.get();
            Duration timeToFirstToken =
                waitStart == null ? Duration.ZERO : Duration.between(waitStart, Instant.now());
            progress.completePhase(CliPhase.WAITING_FOR_MODEL, timeToFirstToken);
            progress.seal();
            if (firstTokenAt != null) {
              firstTokenAt.set(Instant.now());
            }
          }
        };
    delegate.streamAnswer(prompt, onToken, listener);
  }

  private static String retryDetail(int attempt, int maxAttempts, long delayMs, int statusCode) {
    String reason =
        statusCode == 429
            ? "rate limit"
            : statusCode > 0
                ? "HTTP " + statusCode
                : "network";
    return String.format(
        "attempt %d/%d, %.0fs, %s", attempt, maxAttempts, delayMs / 1000.0, reason);
  }
}
