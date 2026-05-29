package vn.ytmusicfabric.client.audio;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.UnsupportedAudioFileException;

public class AudioStreamPlayer {
	private static final int BUFFER_SIZE = 8192;

	private final Path audioFile;
	private final Consumer<PlaybackOutcome> completionHandler;
	private final Object pauseLock = new Object();

	private volatile boolean started;
	private volatile boolean paused;
	private volatile boolean stopRequested;
	private volatile Thread playbackThread;
	private volatile SourceDataLine line;
	private volatile float volumeFactor = 1.0f;

	public AudioStreamPlayer(Path audioFile, Consumer<PlaybackOutcome> completionHandler) {
		this.audioFile = Objects.requireNonNull(audioFile, "audioFile");
		this.completionHandler = Objects.requireNonNull(completionHandler, "completionHandler");
	}

	public void start() {
		if (started) {
			return;
		}

		started = true;
		playbackThread = Thread.ofPlatform()
			.daemon(true)
			.name("ytmusicfabric-audio")
			.unstarted(this::runPlayback);
		playbackThread.start();
	}

	public void pause() {
		paused = true;
		SourceDataLine currentLine = line;
		if (currentLine != null) {
			currentLine.stop();
		}
	}

	public void resume() {
		paused = false;
		SourceDataLine currentLine = line;
		if (currentLine != null) {
			currentLine.start();
		}
		synchronized (pauseLock) {
			pauseLock.notifyAll();
		}
	}

	public void stop() {
		stopRequested = true;
		paused = false;

		synchronized (pauseLock) {
			pauseLock.notifyAll();
		}

		SourceDataLine currentLine = line;
		if (currentLine != null) {
			currentLine.stop();
			currentLine.flush();
		}

		Thread thread = playbackThread;
		if (thread != null) {
			thread.interrupt();
		}
	}

	public void setVolumeFactor(float requestedFactor) {
		volumeFactor = Math.max(0.0f, Math.min(1.0f, requestedFactor));
		SourceDataLine currentLine = line;
		if (currentLine != null) {
			applyVolume(currentLine);
		}
	}

	public long getPlaybackPositionMillis() {
		SourceDataLine currentLine = line;
		if (currentLine == null) {
			return 0L;
		}

		return currentLine.getMicrosecondPosition() / 1_000L;
	}

	private void runPlayback() {
		PlaybackOutcome outcome = new PlaybackOutcome(PlaybackOutcome.Reason.STOPPED, null);

		try (AudioInputStream sourceStream = AudioSystem.getAudioInputStream(audioFile.toFile())) {
			AudioFormat decodedFormat = createDecodedFormat(sourceStream.getFormat());

			try (AudioInputStream playbackStream = AudioSystem.getAudioInputStream(decodedFormat, sourceStream)) {
				SourceDataLine openedLine = openLine(decodedFormat);
				line = openedLine;
				applyVolume(openedLine);
				byte[] buffer = new byte[BUFFER_SIZE];
				int bytesRead;

				openedLine.start();
				while (!stopRequested && (bytesRead = playbackStream.read(buffer, 0, buffer.length)) != -1) {
					waitIfPaused();
					if (stopRequested) {
						break;
					}

					openedLine.write(buffer, 0, bytesRead);
				}

				if (!stopRequested) {
					openedLine.drain();
					outcome = new PlaybackOutcome(PlaybackOutcome.Reason.COMPLETED, null);
				}
			}
		} catch (InterruptedException interruptedException) {
			Thread.currentThread().interrupt();
		} catch (IOException | LineUnavailableException | UnsupportedAudioFileException exception) {
			if (!stopRequested) {
				outcome = new PlaybackOutcome(PlaybackOutcome.Reason.FAILED, exception);
			}
		} finally {
			closeLine();
			completionHandler.accept(outcome);
		}
	}

	private void waitIfPaused() throws InterruptedException {
		synchronized (pauseLock) {
			while (paused && !stopRequested) {
				pauseLock.wait();
			}
		}
	}

	private SourceDataLine openLine(AudioFormat format) throws LineUnavailableException {
		DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
		SourceDataLine openedLine = (SourceDataLine) AudioSystem.getLine(info);
		openedLine.open(format);
		return openedLine;
	}

	private AudioFormat createDecodedFormat(AudioFormat sourceFormat) {
		return new AudioFormat(
			AudioFormat.Encoding.PCM_SIGNED,
			sourceFormat.getSampleRate(),
			16,
			sourceFormat.getChannels(),
			sourceFormat.getChannels() * 2,
			sourceFormat.getSampleRate(),
			false
		);
	}

	private void applyVolume(SourceDataLine currentLine) {
		if (currentLine.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
			FloatControl control = (FloatControl) currentLine.getControl(FloatControl.Type.MASTER_GAIN);
			if (volumeFactor <= 0.0f) {
				control.setValue(control.getMinimum());
				return;
			}

			float requestedDb = (float) (20.0 * Math.log10(volumeFactor));
			float clampedDb = Math.max(control.getMinimum(), Math.min(control.getMaximum(), requestedDb));
			control.setValue(clampedDb);
			return;
		}

		if (currentLine.isControlSupported(FloatControl.Type.VOLUME)) {
			FloatControl control = (FloatControl) currentLine.getControl(FloatControl.Type.VOLUME);
			float clampedVolume = Math.max(control.getMinimum(), Math.min(control.getMaximum(), volumeFactor));
			control.setValue(clampedVolume);
		}
	}

	private void closeLine() {
		SourceDataLine currentLine = line;
		if (currentLine == null) {
			return;
		}

		currentLine.stop();
		currentLine.close();
		line = null;
	}

	public record PlaybackOutcome(Reason reason, Throwable error) {
		public enum Reason {
			COMPLETED,
			STOPPED,
			FAILED
		}
	}
}
