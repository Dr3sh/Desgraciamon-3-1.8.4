package de.maxhenkel.audioplayer.downloader;

import com.sedmelluq.discord.lavaplayer.container.matroska.MatroskaStreamingFile;
import com.sedmelluq.discord.lavaplayer.container.matroska.MatroskaTrackConsumer;
import com.sedmelluq.discord.lavaplayer.container.matroska.format.MatroskaFileTrack;
import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat;
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import de.maxhenkel.audioplayer.AudioConverter;
import de.maxhenkel.audioplayer.AudioManager;
import de.maxhenkel.audioplayer.FileNameManager;
import dev.lavalink.youtube.CannotBeLoaded;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.clients.AndroidMusic;
import dev.lavalink.youtube.clients.AndroidVr;
import dev.lavalink.youtube.clients.ClientConfig;
import dev.lavalink.youtube.clients.ClientOptions;
import dev.lavalink.youtube.clients.Ios;
import dev.lavalink.youtube.clients.MWeb;
import dev.lavalink.youtube.clients.Music;
import dev.lavalink.youtube.clients.Tv;
import dev.lavalink.youtube.clients.Web;
import dev.lavalink.youtube.clients.WebEmbedded;
import dev.lavalink.youtube.clients.skeleton.Client;
import dev.lavalink.youtube.track.YoutubeAudioTrack;
import dev.lavalink.youtube.track.YoutubePersistentHttpStream;
import dev.lavalink.youtube.track.format.FormatInfo;
import dev.lavalink.youtube.track.format.StreamFormat;
import dev.lavalink.youtube.track.format.TrackFormats;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.concentus.OpusDecoder;

import javax.annotation.Nullable;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java audio downloader and converter powered by Lavaplayer, youtube-source, and Concentus.
 * Requires no external binaries (no yt-dlp, no ffmpeg, no Node/Deno runtime).
 */
public class StreamAudioDownloader {

    private static final Logger LOGGER = LogManager.getLogger("audioplayer");
    public static final int MAX_DURATION_SECONDS = 360;
    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNELS = 1;
    private static final int OPUS_MAX_FRAME_SAMPLES = 5760;
    private static final long MAX_MONO_PCM_BYTES = (long) MAX_DURATION_SECONDS * SAMPLE_RATE * 2L;
    // Lavaplayer's FinalPcmAudioFilter always writes stereo sample pairs into its frame buffer,
    // so we decode in stereo 48kHz 16-bit LE and downmix each 4-byte stereo frame to 2-byte mono.
    private static final AudioDataFormat PCM_OUTPUT_FORMAT = StandardAudioDataFormats.DISCORD_PCM_S16_LE;

    private static final Pattern SPOTIFY_TRACK_PATTERN = Pattern.compile("(?i)open\\.spotify\\.com/(?:[a-zA-Z_-]+/)?track/([a-zA-Z0-9]+)");
    private static final Pattern SPOTIFY_EMBED_ARTIST_PATTERN = Pattern.compile("\"artists\"\\s*:\\s*\\[\\s*\\{[^}]*\"name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern DEEZER_TRACK_PATTERN = Pattern.compile("(?i)deezer\\.(?:page\\.link|com)/(?:[a-zA-Z_-]+/)?track/(\\d+)");
    private static final Pattern SOUNDCLOUD_PATTERN = Pattern.compile("(?i)soundcloud\\.com/([^?#]+)");
    private static final Pattern JSON_TITLE_PATTERN = Pattern.compile("\"title\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern OG_TITLE_PATTERN = Pattern.compile("<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern OG_DESC_PATTERN = Pattern.compile("<meta[^>]+property=[\"']og:description[\"'][^>]+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_TITLE_PATTERN = Pattern.compile("<title>([^<]+)</title>", Pattern.CASE_INSENSITIVE);

    private static final Object MANAGER_LOCK = new Object();
    private static volatile AudioPlayerManager playerManager;
    private static volatile YoutubeAudioSourceManager youtubeSourceManager;

    public static class DownloadResult {
        private final UUID soundId;
        private final String title;
        private final Path file;

        public DownloadResult(UUID soundId, String title, Path file) {
            this.soundId = soundId;
            this.title = title;
            this.file = file;
        }

        public UUID getSoundId() {
            return soundId;
        }

        public String getTitle() {
            return title;
        }

        public Path getFile() {
            return file;
        }
    }

    public static class ResolvedQuery {
        public final String downloadTarget;
        public final String title;

        public ResolvedQuery(String downloadTarget, @Nullable String title) {
            this.downloadTarget = downloadTarget;
            this.title = title;
        }
    }

    private static final class DurationLimitReachedException extends RuntimeException {
    }

    private static final Comparator<StreamFormat> AUDIO_FORMAT_PRIORITY_COMPARATOR = Comparator
            .comparingInt(StreamAudioDownloader::audioLanguageTier)
            .thenComparing(f -> !f.isDrc())
            .thenComparingLong(StreamFormat::getBitrate);

    private static final class LanguageAwareClient implements Client {
        private final Client delegate;

        private LanguageAwareClient(Client delegate) {
            this.delegate = delegate;
        }

        @Override
        public PlayabilityStatus getPlayabilityStatus(JsonBrowser contentCheck, boolean isEmbedded) throws CannotBeLoaded {
            return delegate.getPlayabilityStatus(contentCheck, isEmbedded);
        }

        @Override
        public String getUnplayableReason(JsonBrowser playabilityStatus) {
            return delegate.getUnplayableReason(playabilityStatus);
        }

        @Override
        public AudioTrack findSelectedTrack(List<AudioTrack> tracks, String selectedVideoId) {
            return delegate.findSelectedTrack(tracks, selectedVideoId);
        }

        @Override
        public URI transformPlaybackUri(URI originalUri, URI resolvedUri) {
            return delegate.transformPlaybackUri(originalUri, resolvedUri);
        }

        @Override
        public AudioTrack buildAudioTrack(YoutubeAudioSourceManager source, JsonBrowser json, String title, String author, long duration, String videoId, boolean isStream) {
            return delegate.buildAudioTrack(source, json, title, author, duration, videoId, isStream);
        }

        @Override
        public String getIdentifier() {
            return delegate.getIdentifier();
        }

        @Override
        public String getPlayerParams() {
            return delegate.getPlayerParams();
        }

        @Override
        public ClientOptions getOptions() {
            return delegate.getOptions();
        }

        @Override
        public boolean canHandleRequest(String identifier) {
            return delegate.canHandleRequest(identifier);
        }

        @Override
        public boolean supportsFormatLoading() {
            return delegate.supportsFormatLoading();
        }

        @Override
        public boolean isEmbedded() {
            return delegate.isEmbedded();
        }

        @Override
        public boolean supportsOAuth() {
            return delegate.supportsOAuth();
        }

        @Override
        public boolean requirePlayerScript() {
            return delegate.requirePlayerScript();
        }

        @Override
        public void setPlaylistPageCount(int count) {
            delegate.setPlaylistPageCount(count);
        }

        @Override
        public TrackFormats loadFormats(YoutubeAudioSourceManager source, HttpInterface httpInterface, String videoId) throws CannotBeLoaded, IOException {
            return normalizeFormatsByLanguage(delegate.loadFormats(source, httpInterface, videoId));
        }

        @Override
        public AudioItem loadVideo(YoutubeAudioSourceManager source, HttpInterface httpInterface, String videoId) throws CannotBeLoaded, IOException {
            return delegate.loadVideo(source, httpInterface, videoId);
        }

        @Override
        public AudioItem loadSearch(YoutubeAudioSourceManager source, HttpInterface httpInterface, String query) throws CannotBeLoaded, IOException {
            return delegate.loadSearch(source, httpInterface, query);
        }

        @Override
        public AudioItem loadSearchMusic(YoutubeAudioSourceManager source, HttpInterface httpInterface, String query) throws CannotBeLoaded, IOException {
            return delegate.loadSearchMusic(source, httpInterface, query);
        }

        @Override
        public AudioItem loadMix(YoutubeAudioSourceManager source, HttpInterface httpInterface, String mixId, String selectedVideoId) throws CannotBeLoaded, IOException {
            return delegate.loadMix(source, httpInterface, mixId, selectedVideoId);
        }

        @Override
        public AudioItem loadPlaylist(YoutubeAudioSourceManager source, HttpInterface httpInterface, String playlistId, String selectedVideoId) throws CannotBeLoaded, IOException {
            return delegate.loadPlaylist(source, httpInterface, playlistId, selectedVideoId);
        }
    }

    private static void configureSpanishLocale(ClientConfig... configs) {
        // No forzar idioma regional para respetar siempre la pista original nativa del video
    }

    private static void ensureManagerInitialized() {
        if (playerManager != null && youtubeSourceManager != null) {
            return;
        }
        synchronized (MANAGER_LOCK) {
            if (playerManager != null && youtubeSourceManager != null) {
                return;
            }
            configureSpanishLocale(
                    Music.BASE_CONFIG,
                    AndroidVr.BASE_CONFIG,
                    Web.BASE_CONFIG,
                    Ios.BASE_CONFIG,
                    MWeb.BASE_CONFIG,
                    Tv.BASE_CONFIG,
                    WebEmbedded.BASE_CONFIG,
                    AndroidMusic.BASE_CONFIG
            );

            DefaultAudioPlayerManager manager = new DefaultAudioPlayerManager();
            manager.getConfiguration().setOutputFormat(PCM_OUTPUT_FORMAT);
            manager.setTrackStuckThreshold(30_000L);

            YoutubeAudioSourceManager yt = new YoutubeAudioSourceManager(
                    true,
                    new LanguageAwareClient(new Music()),
                    new LanguageAwareClient(new AndroidVr()),
                    new LanguageAwareClient(new Web()),
                    new LanguageAwareClient(new Ios()),
                    new LanguageAwareClient(new MWeb()),
                    new LanguageAwareClient(new Tv()),
                    new LanguageAwareClient(new WebEmbedded()),
                    new LanguageAwareClient(new AndroidMusic())
            );
            manager.registerSourceManager(yt);
            manager.registerSourceManager(SoundCloudAudioSourceManager.createDefault());
            manager.registerSourceManager(new HttpAudioSourceManager());

            youtubeSourceManager = yt;
            playerManager = manager;
        }
    }

    public static boolean isStreamingUrl(String rawUrl) {
        if (rawUrl == null) return false;
        String url = cleanUrl(rawUrl).toLowerCase(Locale.ROOT);
        return url.contains("youtube.com") || url.contains("youtu.be")
                || url.contains("spotify.com") || url.contains("spotify.link")
                || url.contains("deezer.com") || url.contains("deezer.page.link")
                || url.contains("soundcloud.com")
                || url.startsWith("ytsearch:")
                || url.startsWith("ytsearch1:")
                || url.startsWith("scsearch:");
    }

    public static String cleanUrl(String rawUrl) {
        if (rawUrl == null) return "";
        String url = rawUrl.trim();
        if ((url.startsWith("\"") && url.endsWith("\"")) || (url.startsWith("'") && url.endsWith("'"))) {
            url = url.substring(1, url.length() - 1).trim();
        }
        return url;
    }

    public static DownloadResult download(MinecraftServer server, UUID soundId, String rawUrl, @Nullable String customName) throws Exception {
        Path destFolder = AudioManager.getAudioDataFolder(server);
        Files.createDirectories(destFolder);
        Path targetWav = AudioManager.getSoundFile(server, soundId, AudioConverter.AudioType.WAV.getExtension());
        DownloadResult result = downloadToWav(soundId, targetWav, rawUrl, customName);
        FileNameManager.instance().ifPresent(mgr -> mgr.addFileName(soundId, result.getTitle()));
        return result;
    }

    public static DownloadResult downloadToWav(UUID soundId, Path targetWav, String rawUrl, @Nullable String customName) throws Exception {
        ensureManagerInitialized();
        String cleaned = cleanUrl(rawUrl);
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("Empty audio URL or search query");
        }

        ResolvedQuery resolved = resolveInput(cleaned);
        String target = normalizeSearchTarget(resolved.downloadTarget);

        Files.createDirectories(targetWav.toAbsolutePath().getParent());

        AudioTrack track;
        try {
            track = loadSingleTrack(target);
        } catch (Exception firstError) {
            String lower = cleaned.toLowerCase(Locale.ROOT);
            if (lower.contains("soundcloud.com") && !target.startsWith("ytsearch:")) {
                LOGGER.warn("SoundCloud direct lookup failed ({}), trying YouTube search fallback", firstError.getMessage());
                String scQuery = extractSoundCloudQuery(cleaned);
                track = loadSingleTrack("ytsearch:" + scQuery);
            } else {
                throw firstError;
            }
        }

        String rawTrackTitle = resolved.title;
        if (rawTrackTitle == null || rawTrackTitle.isBlank()) {
            AudioTrackInfo info = track.getInfo();
            if (info != null && info.title != null && !info.title.isBlank()) {
                rawTrackTitle = (info.author != null && !info.author.isBlank() && !info.title.toLowerCase(Locale.ROOT).contains(info.author.toLowerCase(Locale.ROOT)))
                        ? info.title + " - " + info.author
                        : info.title;
            } else {
                rawTrackTitle = cleaned;
            }
        }
        String finalTitle = (customName != null && !customName.isBlank())
                ? customName.trim()
                : cleanTitle(rawTrackTitle);

        byte[] pcmBytes;
        try {
            pcmBytes = decodeTrackToMonoPcm(track);
            if (pcmBytes.length == 0) {
                throw new IOException("No audio frames could be decoded for: " + finalTitle);
            }
            writeMonoWav(pcmBytes, targetWav);
        } catch (Exception e) {
            try {
                Files.deleteIfExists(targetWav);
            } catch (IOException ignored) {
            }
            throw e;
        }

        return new DownloadResult(soundId, finalTitle, targetWav);
    }

    private static ResolvedQuery resolveInput(String clean) {
        String lower = clean.toLowerCase(Locale.ROOT);
        if (lower.contains("spotify.com") || lower.contains("spotify.link")) {
            return resolveSpotify(clean);
        }
        if (lower.contains("deezer.com") || lower.contains("deezer.page.link")) {
            return resolveDeezer(clean);
        }
        if (lower.contains("soundcloud.com")) {
            return resolveSoundCloud(clean);
        }
        return resolveYouTube(clean);
    }

    private static String normalizeSearchTarget(String target) {
        if (target.startsWith("ytsearch1:")) {
            return "ytsearch:" + target.substring("ytsearch1:".length()).trim();
        }
        String lower = target.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://")
                && !lower.startsWith("https://")
                && !lower.startsWith("ytsearch:")
                && !lower.startsWith("ytmsearch:")
                && !lower.startsWith("scsearch:")) {
            return "ytsearch:" + target;
        }
        return target;
    }

    private static AudioTrack loadSingleTrack(String identifier) throws Exception {
        CompletableFuture<AudioTrack> future = new CompletableFuture<>();
        playerManager.loadItem(identifier, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                future.complete(track);
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                if (playlist.getSelectedTrack() != null) {
                    future.complete(playlist.getSelectedTrack());
                } else if (!playlist.getTracks().isEmpty()) {
                    future.complete(playlist.getTracks().get(0));
                } else {
                    future.completeExceptionally(new IOException("No playable tracks found in playlist/search"));
                }
            }

            @Override
            public void noMatches() {
                future.completeExceptionally(new IOException("No matches found for: " + identifier));
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                future.completeExceptionally(new IOException("Audio source load failed: " + exception.getMessage(), exception));
            }
        });
        return future.get(30, TimeUnit.SECONDS);
    }

    private static byte[] decodeTrackToMonoPcm(AudioTrack track) throws Exception {
        if (track instanceof YoutubeAudioTrack ytTrack) {
            byte[] pureJavaOpus = tryPureJavaYouTubeOpus(ytTrack);
            if (pureJavaOpus != null && pureJavaOpus.length > 0) {
                return pureJavaOpus;
            }
        }
        return decodeViaAudioPlayer(track.makeClone());
    }

    private static boolean isSupportedAudioFormat(StreamFormat format) {
        FormatInfo info = format.getInfo();
        if (info == null) {
            return false;
        }
        return !("audio/webm".equals(info.mimeType) && format.getAudioChannels() > 2L);
    }

    private static String extractXtags(StreamFormat format) {
        try {
            URI uri = format.getUrl();
            if (uri == null) return "";
            String rawQuery = uri.getRawQuery();
            if (rawQuery == null || rawQuery.isEmpty()) {
                String raw = uri.toString();
                int qIdx = raw.indexOf('?');
                if (qIdx < 0 || qIdx + 1 >= raw.length()) return "";
                rawQuery = raw.substring(qIdx + 1);
            }
            for (String param : rawQuery.split("&")) {
                int eq = param.indexOf('=');
                if (eq <= 0) continue;
                if ("xtags".equals(param.substring(0, eq))) {
                    String decoded = URLDecoder.decode(param.substring(eq + 1), StandardCharsets.UTF_8);
                    if (decoded.contains("%3D") || decoded.contains("%3A")) {
                        decoded = URLDecoder.decode(decoded, StandardCharsets.UTF_8);
                    }
                    return decoded;
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static int audioLanguageTier(StreamFormat format) {
        String xtags = extractXtags(format);
        if (xtags.isEmpty()) {
            return 2;
        }
        String acont = "";
        String lang = "";
        for (String part : xtags.split("[:&;]")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String k = part.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String v = part.substring(eq + 1).trim().toLowerCase(Locale.ROOT);
            if ("acont".equals(k)) {
                acont = v;
            } else if ("lang".equals(k)) {
                lang = v;
            }
        }
        boolean isOriginal = "original".equals(acont);
        boolean isDubbed = acont.startsWith("dubbed") || "descriptive".equals(acont);

        if (isOriginal) {
            return 4;
        }
        if (!isDubbed) {
            return 2;
        }
        return 0;
    }

    private static TrackFormats normalizeFormatsByLanguage(@Nullable TrackFormats formats) {
        if (formats == null || formats.getFormats() == null || formats.getFormats().isEmpty()) {
            return formats;
        }
        int bestTier = -1;
        for (StreamFormat f : formats.getFormats()) {
            if (!isSupportedAudioFormat(f)) continue;
            bestTier = Math.max(bestTier, audioLanguageTier(f));
        }
        if (bestTier < 0) {
            return formats;
        }

        List<StreamFormat> updated = new ArrayList<>(formats.getFormats().size());
        for (StreamFormat f : formats.getFormats()) {
            boolean keepDefault = isSupportedAudioFormat(f) && audioLanguageTier(f) == bestTier;
            updated.add(new StreamFormat(
                    f.getType(),
                    f.getItag(),
                    f.getBitrate(),
                    f.getContentLength(),
                    f.getAudioChannels(),
                    f.getUrl().toString(),
                    f.getNParameter(),
                    f.getSignature(),
                    f.getSignatureKey(),
                    keepDefault,
                    f.isDrc()
            ));
        }
        return new TrackFormats(updated, formats.getPlayerScriptUrl());
    }

    @Nullable
    private static byte[] tryPureJavaYouTubeOpus(YoutubeAudioTrack ytTrack) {
        try (HttpInterface httpInterface = youtubeSourceManager.getInterface()) {
            for (Client client : youtubeSourceManager.getClients()) {
                if (!client.supportsFormatLoading()) {
                    continue;
                }
                TrackFormats formats;
                try {
                    formats = client.loadFormats(youtubeSourceManager, httpInterface, ytTrack.getIdentifier());
                } catch (Throwable ignored) {
                    continue;
                }
                if (formats == null || formats.getFormats() == null || formats.getFormats().isEmpty()) {
                    continue;
                }

                StreamFormat opusFormat = formats.getFormats().stream()
                        .filter(f -> f.getInfo() == FormatInfo.WEBM_OPUS && f.getContentLength() > 0 && f.getContentLength() < Long.MAX_VALUE)
                        .max(AUDIO_FORMAT_PRIORITY_COMPARATOR)
                        .orElse(null);
                if (opusFormat == null) {
                    continue;
                }

                try {
                    URI playbackUri = opusFormat.getUrl();
                    if (client.requirePlayerScript()) {
                        playbackUri = youtubeSourceManager.getCipherManager().resolveFormatUrl(
                                httpInterface,
                                formats.getPlayerScriptUrl(),
                                opusFormat
                        );
                    }
                    playbackUri = client.transformPlaybackUri(opusFormat.getUrl(), playbackUri);
                    byte[] pcm = decodeWebmOpusStream(httpInterface, playbackUri, opusFormat.getContentLength());
                    if (pcm.length > 0) {
                        return pcm;
                    }
                } catch (Throwable t) {
                    LOGGER.debug("Pure-Java Opus client {} failed for {}: {}", client.getIdentifier(), ytTrack.getIdentifier(), t.getMessage());
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("Pure-Java YouTube path skipped: {}", t.getMessage());
        }
        return null;
    }

    private static byte[] decodeWebmOpusStream(HttpInterface httpInterface, URI signedUri, long contentLength) throws Exception {
        ByteArrayOutputStream pcmOut = new ByteArrayOutputStream(256 * 1024);
        try (YoutubePersistentHttpStream stream = new YoutubePersistentHttpStream(httpInterface, signedUri, contentLength)) {
            MatroskaStreamingFile matroska = new MatroskaStreamingFile(stream);
            matroska.readFile();

            MatroskaFileTrack opusTrack = null;
            for (MatroskaFileTrack candidate : matroska.getTrackList()) {
                if (candidate.type == MatroskaFileTrack.Type.AUDIO && "A_OPUS".equalsIgnoreCase(candidate.codecId)) {
                    opusTrack = candidate;
                    break;
                }
            }
            if (opusTrack == null) {
                throw new IOException("No A_OPUS track found in WebM stream");
            }

            final MatroskaFileTrack selectedTrack = opusTrack;
            final int trackChannels = (selectedTrack.audio != null && selectedTrack.audio.channels > 0)
                    ? Math.min(2, selectedTrack.audio.channels)
                    : 2;
            final OpusDecoder decoder = new OpusDecoder(SAMPLE_RATE, trackChannels);
            final short[] sampleBuf = new short[OPUS_MAX_FRAME_SAMPLES * trackChannels];

            MatroskaTrackConsumer consumer = new MatroskaTrackConsumer() {
                @Override
                public MatroskaFileTrack getTrack() {
                    return selectedTrack;
                }

                @Override
                public void initialise() {
                }

                @Override
                public void seekPerformed(long requestedTimecode, long providedTimecode) {
                }

                @Override
                public void flush() {
                }

                @Override
                public void consume(ByteBuffer data) {
                    if (pcmOut.size() >= MAX_MONO_PCM_BYTES) {
                        throw new DurationLimitReachedException();
                    }
                    byte[] packet = new byte[data.remaining()];
                    data.get(packet);
                    if (packet.length == 0) {
                        return;
                    }
                    try {
                        int decodedFrames = decoder.decode(packet, 0, packet.length, sampleBuf, 0, OPUS_MAX_FRAME_SAMPLES, false);
                        writeDownmixedMonoLe16(sampleBuf, decodedFrames, trackChannels, pcmOut);
                    } catch (Exception e) {
                        throw new IllegalStateException("Failed decoding Opus packet: " + e.getMessage(), e);
                    }
                }

                @Override
                public void close() {
                }
            };

            try {
                matroska.provideFrames(consumer);
            } catch (DurationLimitReachedException ignored) {
                // Capped cleanly at MAX_DURATION_SECONDS
            }
        }
        return pcmOut.toByteArray();
    }

    private static void writeDownmixedMonoLe16(short[] interleaved, int frames, int channels, ByteArrayOutputStream out) {
        for (int i = 0; i < frames; i++) {
            if (out.size() + 2 > MAX_MONO_PCM_BYTES) {
                throw new DurationLimitReachedException();
            }
            int sample;
            if (channels == 1) {
                sample = interleaved[i];
            } else {
                sample = ((int) interleaved[i * channels] + (int) interleaved[i * channels + 1]) / 2;
            }
            out.write(sample & 0xFF);
            out.write((sample >> 8) & 0xFF);
        }
    }

    private static byte[] decodeViaAudioPlayer(AudioTrack track) throws Exception {
        AudioPlayer player = playerManager.createPlayer();
        AtomicBoolean ended = new AtomicBoolean(false);
        AtomicReference<Throwable> trackError = new AtomicReference<>();

        player.addListener(new AudioEventAdapter() {
            @Override
            public void onTrackEnd(AudioPlayer audioPlayer, AudioTrack finishedTrack, AudioTrackEndReason endReason) {
                ended.set(true);
            }

            @Override
            public void onTrackException(AudioPlayer audioPlayer, AudioTrack failedTrack, FriendlyException exception) {
                trackError.compareAndSet(null, exception);
                ended.set(true);
            }
        });

        ByteArrayOutputStream pcmOut = new ByteArrayOutputStream(256 * 1024);
        long startMs = System.currentTimeMillis();
        long hardTimeoutMs = 120_000L;

        try {
            player.playTrack(track);
            while (pcmOut.size() < MAX_MONO_PCM_BYTES) {
                if (System.currentTimeMillis() - startMs > hardTimeoutMs) {
                    break;
                }
                AudioFrame frame = player.provide(250, TimeUnit.MILLISECONDS);
                if (frame != null) {
                    if (frame.isTerminator()) {
                        break;
                    }
                    byte[] data = frame.getData();
                    writeStereoLe16AsMono(data, frame.getDataLength(), pcmOut);
                    continue;
                }
                if (ended.get()) {
                    // Drain any buffered final frames briefly
                    AudioFrame trailing = player.provide(50, TimeUnit.MILLISECONDS);
                    if (trailing != null && !trailing.isTerminator() && trailing.getDataLength() > 0) {
                        writeStereoLe16AsMono(trailing.getData(), trailing.getDataLength(), pcmOut);
                        continue;
                    }
                    break;
                }
            }
        } finally {
            player.destroy();
        }

        if (pcmOut.size() == 0 && trackError.get() != null) {
            throw new IOException("Audio decode failed: " + trackError.get().getMessage(), trackError.get());
        }
        return pcmOut.toByteArray();
    }

    private static void writeStereoLe16AsMono(byte[] stereoLe16, int length, ByteArrayOutputStream out) {
        int limit = Math.min(length, stereoLe16.length);
        for (int i = 0; i + 3 < limit && out.size() + 2 <= MAX_MONO_PCM_BYTES; i += 4) {
            short left = (short) ((stereoLe16[i] & 0xFF) | (stereoLe16[i + 1] << 8));
            short right = (short) ((stereoLe16[i + 2] & 0xFF) | (stereoLe16[i + 3] << 8));
            int mono = ((int) left + (int) right) / 2;
            out.write(mono & 0xFF);
            out.write((mono >> 8) & 0xFF);
        }
    }

    private static void writeMonoWav(byte[] pcmBytes, Path targetWav) throws IOException {
        int evenLen = pcmBytes.length - (pcmBytes.length % 2);
        long frameLength = evenLen / 2L;
        try (ByteArrayInputStream bais = new ByteArrayInputStream(pcmBytes, 0, evenLen);
             AudioInputStream ais = new AudioInputStream(bais, AudioConverter.FORMAT, frameLength)) {
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, targetWav.toFile());
        }
    }

    private static String extractSoundCloudQuery(String cleanUrl) {
        Matcher scMatcher = SOUNDCLOUD_PATTERN.matcher(cleanUrl);
        if (scMatcher.find()) {
            return scMatcher.group(1).replace('/', ' ').replace('-', ' ');
        }
        return cleanUrl;
    }

    public static ResolvedQuery resolveYouTube(String url) {
        return new ResolvedQuery(url, null);
    }

    public static ResolvedQuery resolveSoundCloud(String url) {
        return new ResolvedQuery(url, null);
    }

    public static ResolvedQuery resolveSpotify(String url) {
        String title = null;
        String artist = null;
        String trackId = null;

        Matcher directTrack = SPOTIFY_TRACK_PATTERN.matcher(url);
        if (directTrack.find()) {
            trackId = directTrack.group(1);
        }

        try {
            String oembedUrl = "https://open.spotify.com/oembed?url=" + URLEncoder.encode(url, StandardCharsets.UTF_8);
            String json = httpGet(oembedUrl);
            Matcher mTitle = JSON_TITLE_PATTERN.matcher(json);
            if (mTitle.find()) {
                title = unescapeJson(mTitle.group(1));
            }
        } catch (Exception ignored) {
        }

        try {
            String html = httpGet(url);
            if (trackId == null) {
                Matcher htmlTrack = SPOTIFY_TRACK_PATTERN.matcher(html);
                if (htmlTrack.find()) {
                    trackId = htmlTrack.group(1);
                }
            }
            if (title == null || title.isBlank()) {
                Matcher ogTitle = OG_TITLE_PATTERN.matcher(html);
                if (ogTitle.find()) {
                    title = ogTitle.group(1);
                }
            }
            Matcher ogDesc = OG_DESC_PATTERN.matcher(html);
            if (ogDesc.find()) {
                String desc = ogDesc.group(1);
                String[] parts = desc.split("\\s*[\\u00B7|]\\s*");
                if (parts.length > 0 && !parts[0].equalsIgnoreCase("Song") && !parts[0].equalsIgnoreCase("Listen")) {
                    artist = parts[0].trim();
                } else if (parts.length > 1) {
                    artist = parts[1].trim();
                }
            }
        } catch (Exception ignored) {
        }

        if ((artist == null || artist.isBlank()) && trackId != null) {
            try {
                String embedHtml = httpGet("https://open.spotify.com/embed/track/" + trackId);
                Matcher artistMatcher = SPOTIFY_EMBED_ARTIST_PATTERN.matcher(embedHtml);
                if (artistMatcher.find()) {
                    artist = unescapeJson(artistMatcher.group(1)).trim();
                }
                if (title == null || title.isBlank()) {
                    Matcher htmlTitle = HTML_TITLE_PATTERN.matcher(embedHtml);
                    if (htmlTitle.find()) {
                        title = htmlTitle.group(1).replace("| Spotify", "").trim();
                    }
                }
            } catch (Exception ignored) {
            }
        }

        String displayTitle = (title != null && !title.isBlank()) ? title : "Spotify Track";
        if (artist != null && !artist.isBlank() && !displayTitle.toLowerCase(Locale.ROOT).contains(artist.toLowerCase(Locale.ROOT))) {
            displayTitle = displayTitle + " - " + artist;
        }
        String searchQuery = (title != null && !title.isBlank())
                ? "ytsearch:" + displayTitle + " audio"
                : extractSlugQuery(url, "Spotify Track");

        return new ResolvedQuery(searchQuery, (title != null && !title.isBlank()) ? displayTitle : null);
    }

    public static ResolvedQuery resolveDeezer(String url) {
        String title = null;
        String artist = null;

        Matcher trackMatcher = DEEZER_TRACK_PATTERN.matcher(url);
        if (trackMatcher.find()) {
            try {
                String json = httpGet("https://api.deezer.com/track/" + trackMatcher.group(1));
                Matcher mTitle = Pattern.compile("\"title_short\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
                if (mTitle.find()) {
                    title = unescapeJson(mTitle.group(1));
                }
                Matcher mArtist = Pattern.compile("\"artist\"\\s*:\\s*\\{[^}]*\"name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
                if (mArtist.find()) {
                    artist = unescapeJson(mArtist.group(1));
                }
            } catch (Exception ignored) {
            }
        }

        if (title == null || title.isBlank()) {
            try {
                String html = httpGet(url);
                Matcher htmlTitle = HTML_TITLE_PATTERN.matcher(html);
                if (htmlTitle.find()) {
                    String raw = htmlTitle.group(1).replace(" - Deezer", "").trim();
                    if (!raw.isBlank()) {
                        title = raw;
                    }
                }
            } catch (Exception ignored) {
            }
        }

        String displayTitle = (title != null && !title.isBlank()) ? title : "Deezer Track";
        if (artist != null && !artist.isBlank() && !displayTitle.toLowerCase(Locale.ROOT).contains(artist.toLowerCase(Locale.ROOT))) {
            displayTitle = displayTitle + " - " + artist;
        }
        String searchQuery = (title != null && !title.isBlank())
                ? "ytsearch:" + displayTitle + " audio"
                : extractSlugQuery(url, "Deezer Track");

        return new ResolvedQuery(searchQuery, (title != null && !title.isBlank()) ? displayTitle : null);
    }

    private static String extractSlugQuery(String url, String fallback) {
        Matcher trackMatcher = SPOTIFY_TRACK_PATTERN.matcher(url);
        if (trackMatcher.find()) {
            return "ytsearch:spotify track " + trackMatcher.group(1);
        }
        return "ytsearch:" + fallback;
    }

    private static String httpGet(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        try (InputStream is = conn.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }

    private static String unescapeJson(String text) {
        return text.replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\/", "/");
    }

    public static String cleanTitle(@Nullable String rawTitle) {
        if (rawTitle == null || rawTitle.isBlank()) {
            return "Custom Audio";
        }
        String title = rawTitle.trim();
        title = title.replaceAll("(?i)\\s*[\\[\\(][^\\]\\)]*(?:official\\s*(?:music\\s*)?(?:video|audio|lyric|lyrics|visualizer|hd|4k)|music\\s*video|lyrics?|audio\\s*only|video\\s*oficial|audio\\s*oficial)[^\\]\\)]*[\\]\\)]", "");
        title = title.replaceAll("\\s{2,}", " ").trim();
        return title.isBlank() ? rawTitle.trim() : title;
    }
}
