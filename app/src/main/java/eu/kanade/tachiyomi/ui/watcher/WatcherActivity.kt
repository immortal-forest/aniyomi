package eu.kanade.tachiyomi.ui.watcher

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.github.vkay94.dtpv.DoubleTapPlayerView
import com.github.vkay94.dtpv.youtube.YouTubeOverlay
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.DefaultRenderersFactory
import com.google.android.exoplayer2.ExoPlaybackException
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackParameters
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.analytics.AnalyticsCollector
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.source.MediaSourceFactory
import com.google.android.exoplayer2.source.TrackGroupArray
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.trackselection.TrackSelectionArray
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.upstream.HttpDataSource.HttpDataSourceException
import com.google.android.exoplayer2.upstream.HttpDataSource.InvalidResponseCodeException
import com.google.android.exoplayer2.util.Clock
import com.google.android.exoplayer2.util.MimeTypes
import com.google.android.exoplayer2.util.Util
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceManager
import eu.kanade.tachiyomi.animesource.LocalAnimeSource
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.data.database.AnimeDatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Anime
import eu.kanade.tachiyomi.data.database.models.AnimeHistory
import eu.kanade.tachiyomi.data.database.models.Episode
import eu.kanade.tachiyomi.data.download.AnimeDownloadManager
import eu.kanade.tachiyomi.data.download.model.AnimeDownload
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.ui.anime.episode.EpisodeItem
import eu.kanade.tachiyomi.ui.webview.WebViewActivityAnime
import eu.kanade.tachiyomi.util.lang.awaitSingle
import eu.kanade.tachiyomi.util.lang.launchIO
import eu.kanade.tachiyomi.util.lang.withIOContext
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.hideBar
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import rx.schedulers.Schedulers
import timber.log.Timber
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.util.Date

const val STATE_RESUME_WINDOW = "resumeWindow"
const val STATE_RESUME_POSITION = "resumePosition"
const val STATE_PLAYER_FULLSCREEN = "playerFullscreen"
const val STATE_PLAYER_PLAYING = "playerOnPlay"

class WatcherActivity : AppCompatActivity() {

    private val preferences: PreferencesHelper = Injekt.get()
    private val incognitoMode = preferences.incognitoMode().get()
    private val db: AnimeDatabaseHelper = Injekt.get()
    private lateinit var exoPlayer: SimpleExoPlayer
    private lateinit var dataSourceFactory: DataSource.Factory
    private lateinit var mediaSourceFactory: MediaSourceFactory
    private lateinit var playerView: DoubleTapPlayerView
    private lateinit var youTubeDoubleTap: YouTubeOverlay
    private lateinit var skipBtn: TextView
    private lateinit var nextBtn: ImageButton
    private lateinit var prevBtn: ImageButton
    private lateinit var backBtn: TextView
    private lateinit var settingsBtn: ImageButton
    private lateinit var captchaBtn: TextView
    private lateinit var title: TextView

    private lateinit var episode: Episode
    private lateinit var anime: Anime
    private lateinit var source: AnimeSource
    private lateinit var userAgentString: String
    private lateinit var uri: String
    private lateinit var videos: List<Video>
    private var currentQuality = 0

    private var duration: Long = 0
    private var currentWindow = 0
    private var playbackPosition = 0L
    private var isFullscreen = false
    private var isPlayerPlaying = true
    private var mediaItem = MediaItem.Builder()
        .setUri("bruh")
        .setMimeType(MimeTypes.VIDEO_MP4)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.watcher_activity)
        window.decorView.setOnSystemUiVisibilityChangeListener {
            window.hideBar()
        }
        playerView = findViewById(R.id.player_view)
        youTubeDoubleTap = findViewById(R.id.youtube_overlay)
        youTubeDoubleTap
            .performListener(object : YouTubeOverlay.PerformListener {
                override fun onAnimationStart() {
                    // Do UI changes when circle scaling animation starts (e.g. hide controller views)
                    youTubeDoubleTap.visibility = View.VISIBLE
                }

                override fun onAnimationEnd() {
                    // Do UI changes when circle scaling animation starts (e.g. show controller views)
                    youTubeDoubleTap.visibility = View.GONE
                }
            })
        backBtn = findViewById(R.id.exo_overlay_back)
        title = findViewById(R.id.exo_overlay_title)
        skipBtn = findViewById(R.id.watcher_controls_skip_btn)
        nextBtn = findViewById(R.id.watcher_controls_next)
        prevBtn = findViewById(R.id.watcher_controls_prev)
        settingsBtn = findViewById(R.id.watcher_controls_settings)
        captchaBtn = findViewById(R.id.watcher_controls_captcha_btn)

        anime = intent.getSerializableExtra("anime") as Anime
        episode = intent.getSerializableExtra("episode") as Episode
        title.text = baseContext.getString(R.string.playertitle, anime.title, episode.name)
        source = Injekt.get<AnimeSourceManager>().getOrStub(anime.source)
        userAgentString = WebSettings.getDefaultUserAgent(this)
        videos = runBlocking { awaitVideoList() }
        if (videos.isEmpty()) {
            baseContext.toast("Cannot play episode")
            super.onBackPressed()
        }
        if (videos.lastIndex > 0) settingsBtn.visibility = View.VISIBLE
        uri = videos.first().videoUrl ?: videos.first().uri!!.toString()
        dataSourceFactory = if (EpisodeLoader.isDownloaded(episode, anime) || source is LocalAnimeSource) {
            DefaultDataSourceFactory(this)
        } else {
            newDataSourceFactory()
        }
        mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMimeType(getMime(uri))
            .build()
        playbackPosition = episode.last_second_seen

        if (savedInstanceState != null) {
            currentWindow = savedInstanceState.getInt(STATE_RESUME_WINDOW)
            playbackPosition = intent.extras!!.getLong("second")
            isFullscreen = savedInstanceState.getBoolean(STATE_PLAYER_FULLSCREEN)
            isPlayerPlaying = savedInstanceState.getBoolean(STATE_PLAYER_PLAYING)
        }
    }

    private fun newDataSourceFactory(): DefaultHttpDataSource.Factory {
        return DefaultHttpDataSource.Factory().apply {
            val headers = (source as AnimeHttpSource).headers.toMultimap().mapValues { it.value.getOrNull(0) ?: "" }.toMutableMap()
            userAgentString = (source as AnimeHttpSource).headers["User-Agent"] ?: ""
            setDefaultRequestProperties(headers)
            setUserAgent(if (userAgentString.isNotEmpty()) userAgentString else headers["User-Agent"])
        }
    }

    private fun getMime(uri: String): String {
        return when (uri.substringAfterLast(".")) {
            "mp4" -> MimeTypes.VIDEO_MP4
            "mkv" -> MimeTypes.APPLICATION_MATROSKA
            "m3u8" -> MimeTypes.APPLICATION_M3U8
            else -> MimeTypes.VIDEO_MP4
        }
    }

    private fun initPlayer() {
        exoPlayer = newPlayer().apply {
            playWhenReady = isPlayerPlaying
            seekTo(currentWindow, playbackPosition)
            setMediaSource(mediaSourceFactory.createMediaSource(mediaItem), episode.last_second_seen)
            prepare()
        }
        exoPlayer.addListener(PlayerEventListener(playerView, captchaBtn, baseContext))
        backBtn.setOnClickListener {
            onBackPressed()
        }
        settingsBtn.setOnClickListener {
            settings()
        }
        captchaBtn.setOnClickListener {
            captcha()
        }
        skipBtn.setOnClickListener { exoPlayer.seekTo(exoPlayer.currentPosition + 85000) }
        nextBtn.setOnClickListener {
            nextEpisode()
        }
        prevBtn.setOnClickListener {
            previousEpisode()
        }
        youTubeDoubleTap.player(exoPlayer)
        playerView.player = exoPlayer
        duration = exoPlayer.duration
    }

    override fun onBackPressed() {
        releasePlayer()
        super.onBackPressed()
    }

    private fun releasePlayer() {
        youTubeDoubleTap.player(exoPlayer)
        isPlayerPlaying = exoPlayer.playWhenReady
        playbackPosition = exoPlayer.currentPosition
        currentWindow = exoPlayer.currentWindowIndex
        saveEpisodeHistory(EpisodeItem(episode, anime))
        setEpisodeProgress(episode, anime, exoPlayer.currentPosition, exoPlayer.duration)
        exoPlayer.release()
    }

    private suspend fun awaitVideoList(): List<Video> {
        return withIOContext { EpisodeLoader.getLinks(episode, anime, source).awaitSingle() }
    }

    private fun nextEpisode() {
        saveEpisodeHistory(EpisodeItem(episode, anime))
        setEpisodeProgress(episode, anime, exoPlayer.currentPosition, exoPlayer.duration)
        val oldEpisode = episode
        episode = getNextEpisode(episode, anime)
        if (oldEpisode == episode) return
        title.text = baseContext.getString(R.string.playertitle, anime.title, episode.name)
        videos = runBlocking { awaitVideoList() }
        settingsBtn.visibility = if (videos.lastIndex > 0) View.VISIBLE else View.GONE
        uri = videos.first().videoUrl ?: videos.first().uri!!.toString()
        currentQuality = 0
        mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMimeType(getMime(uri))
            .build()
        changePlayer(episode.last_second_seen)
    }

    private fun previousEpisode() {
        saveEpisodeHistory(EpisodeItem(episode, anime))
        setEpisodeProgress(episode, anime, exoPlayer.currentPosition, exoPlayer.duration)
        val oldEpisode = episode
        episode = getPreviousEpisode(episode, anime)
        if (oldEpisode == episode) return
        title.text = baseContext.getString(R.string.playertitle, anime.title, episode.name)
        videos = runBlocking { awaitVideoList() }
        settingsBtn.visibility = if (videos.lastIndex > 0) View.VISIBLE else View.GONE
        uri = videos.first().videoUrl ?: videos.first().uri!!.toString()
        currentQuality = 0
        mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMimeType(getMime(uri))
            .build()
        changePlayer(episode.last_second_seen)
    }

    private fun settings() {
        val nextQuality = if (currentQuality == videos.lastIndex) 0 else currentQuality + 1
        baseContext.toast(videos[nextQuality].quality, Toast.LENGTH_SHORT)
        uri = videos[nextQuality].videoUrl ?: videos.first().uri!!.toString()
        currentQuality = nextQuality
        mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMimeType(getMime(uri))
            .build()
        changePlayer(exoPlayer.currentPosition)
    }

    private fun captcha() {
        exoPlayer.release()
        val intent = WebViewActivityAnime.newIntent(this, uri, source.id, anime.title + episode.name, true)
        startActivityForResult(intent, REQUEST_COOKIES)
        captchaBtn.visibility = View.GONE
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_COOKIES && resultCode == REQUEST_COOKIES) {
            val cookies = data?.data.toString()
            userAgentString = data?.getStringExtra("User-Agent")!!
            dataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(userAgentString)
                .setDefaultRequestProperties(mapOf(Pair("cookie", cookies)))
            mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        }
    }

    private fun changePlayer(resumeAt: Long) {
        dataSourceFactory = if (EpisodeLoader.isDownloaded(episode, anime)) {
            DefaultDataSourceFactory(this)
        } else {
            DefaultHttpDataSource.Factory().apply {
                setDefaultRequestProperties(mapOf(Pair("cookie", CookieManager.getInstance().getCookie(uri))))
                setUserAgent(userAgentString)
            }
        }
        mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        exoPlayer.release()
        exoPlayer = newPlayer()
        exoPlayer.setMediaSource(mediaSourceFactory.createMediaSource(mediaItem), resumeAt)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        exoPlayer.addListener(PlayerEventListener(playerView, captchaBtn, baseContext))
        youTubeDoubleTap.player(exoPlayer)
        playerView.player = exoPlayer
    }

    private fun newPlayer(): SimpleExoPlayer {
        return SimpleExoPlayer.Builder(
            this,
            DefaultRenderersFactory(this),
            DefaultTrackSelector(this),
            mediaSourceFactory,
            DefaultLoadControl(),
            DefaultBandwidthMeter.Builder(this).build(),
            AnalyticsCollector(Clock.DEFAULT)
        ).build()
    }

    class PlayerEventListener(private val playerView: DoubleTapPlayerView, val captchaBtn: TextView, val baseContext: Context) : Player.EventListener {
        override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
            playerView.keepScreenOn = !(
                playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED ||
                    !playWhenReady
                )
        }
        override fun onTracksChanged(trackGroups: TrackGroupArray, trackSelections: TrackSelectionArray) {}
        override fun onLoadingChanged(isLoading: Boolean) {}
        override fun onRepeatModeChanged(repeatMode: Int) {}
        override fun onPlayerError(error: ExoPlaybackException) {
            if (error.type == ExoPlaybackException.TYPE_SOURCE) {
                val cause: IOException = error.sourceException
                if (cause is HttpDataSourceException) {
                    // An HTTP error occurred.
                    // This is the request for which the error occurred.
                    val requestDataSpec = cause.dataSpec
                    for (header in requestDataSpec.httpRequestHeaders) {
                        var message = ""
                        message += header.key + " - " + header.value
                    }
                    // It's possible to find out more about the error both by casting and by
                    // querying the cause.
                    if (cause is InvalidResponseCodeException) {
                        // Cast to InvalidResponseCodeException and retrieve the response code,
                        // message and headers.
                        val errorMessage =
                            "Error " + cause.responseCode.toString() + ", try solving a captcha"
                        captchaBtn.visibility = View.VISIBLE
                        baseContext.toast(errorMessage, Toast.LENGTH_SHORT)
                    } else {
                        cause.cause
                        // Try calling httpError.getCause() to retrieve the underlying cause,
                        // although note that it may be null.
                    }
                }
            }
        }
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {}
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_RESUME_WINDOW, exoPlayer.currentWindowIndex)
        outState.putLong(STATE_RESUME_POSITION, exoPlayer.currentPosition)
        outState.putBoolean(STATE_PLAYER_FULLSCREEN, isFullscreen)
        outState.putBoolean(STATE_PLAYER_PLAYING, isPlayerPlaying)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        if (Util.SDK_INT > 23) {
            initPlayer()
            playerView.onResume()
        }
    }

    override fun onResume() {
        super.onResume()
        if (Util.SDK_INT <= 23) {
            initPlayer()
            playerView.onResume()
        }
    }

    override fun onPause() {
        super.onPause()
        if (Util.SDK_INT <= 23) {
            playerView.onPause()
            releasePlayer()
        }
    }

    override fun onStop() {
        super.onStop()
        if (Util.SDK_INT > 23) {
            playerView.onPause()
            releasePlayer()
        }
    }

    private fun saveEpisodeHistory(episode: EpisodeItem) {
        if (!incognitoMode) {
            val history = AnimeHistory.create(episode.episode).apply { last_seen = Date().time }
            db.updateAnimeHistoryLastSeen(history).asRxCompletable()
                .onErrorComplete()
                .subscribeOn(Schedulers.io())
                .subscribe()
        }
    }

    private fun setEpisodeProgress(episode: Episode, anime: Anime, seconds: Long, totalSeconds: Long) {
        if (!incognitoMode) {
            if (totalSeconds > 0L) {
                episode.last_second_seen = seconds
                episode.total_seconds = totalSeconds
                val progress = preferences.progressPreference()
                if (!episode.seen) episode.seen = episode.last_second_seen >= episode.total_seconds * progress
                val episodes = listOf(EpisodeItem(episode, anime))
                launchIO {
                    db.updateEpisodesProgress(episodes).executeAsBlocking()
                    if (preferences.autoUpdateTrack() && episode.seen) {
                        updateTrackEpisodeSeen(episode)
                    }
                    if (preferences.removeAfterMarkedAsRead()) {
                        launchIO {
                            try {
                                val downloadManager: AnimeDownloadManager = Injekt.get()
                                val source: AnimeSource = Injekt.get<AnimeSourceManager>().getOrStub(anime.source)
                                downloadManager.deleteEpisodes(episodes, anime, source).forEach {
                                    if (it is EpisodeItem) {
                                        it.status = AnimeDownload.State.NOT_DOWNLOADED
                                        it.download = null
                                    }
                                }
                            } catch (e: Throwable) {
                                throw e
                            }
                        }
                    }
                }
            }
        }
    }

    private fun updateTrackEpisodeSeen(episode: Episode) {
        val episodeSeen = episode.episode_number.toInt()

        val trackManager = Injekt.get<TrackManager>()

        launchIO {
            db.getTracks(anime).executeAsBlocking()
                .mapNotNull { track ->
                    val service = trackManager.getService(track.sync_id)
                    if (service != null && service.isLogged && episodeSeen > track.last_episode_seen) {
                        track.last_episode_seen = episodeSeen

                        // We want these to execute even if the presenter is destroyed and leaks
                        // for a while. The view can still be garbage collected.
                        async {
                            runCatching {
                                service.update(track)
                                db.insertTrack(track).executeAsBlocking()
                            }
                        }
                    } else {
                        null
                    }
                }
                .awaitAll()
                .mapNotNull { it.exceptionOrNull() }
                .forEach { Timber.w(it) }
        }
    }

    private fun getNextEpisode(episode: Episode, anime: Anime): Episode {
        val sortFunction: (Episode, Episode) -> Int = when (anime.sorting) {
            Anime.EPISODE_SORTING_SOURCE -> { c1, c2 -> c2.source_order.compareTo(c1.source_order) }
            Anime.EPISODE_SORTING_NUMBER -> { c1, c2 -> c1.episode_number.compareTo(c2.episode_number) }
            Anime.EPISODE_SORTING_UPLOAD_DATE -> { c1, c2 -> c1.date_upload.compareTo(c2.date_upload) }
            else -> throw NotImplementedError("Unknown sorting method")
        }

        val episodes = db.getEpisodes(anime).executeAsBlocking()
            .sortedWith { e1, e2 -> sortFunction(e1, e2) }

        val currEpisodeIndex = episodes.indexOfFirst { episode.id == it.id }
        val episodeNumber = episode.episode_number
        return ((currEpisodeIndex + 1) until episodes.size)
            .map { episodes[it] }
            .firstOrNull {
                it.episode_number > episodeNumber &&
                    it.episode_number <= episodeNumber + 1
            } ?: episode
    }

    private fun getPreviousEpisode(episode: Episode, anime: Anime): Episode {
        val sortFunction: (Episode, Episode) -> Int = when (anime.sorting) {
            Anime.EPISODE_SORTING_SOURCE -> { c1, c2 -> c2.source_order.compareTo(c1.source_order) }
            Anime.EPISODE_SORTING_NUMBER -> { c1, c2 -> c1.episode_number.compareTo(c2.episode_number) }
            Anime.EPISODE_SORTING_UPLOAD_DATE -> { c1, c2 -> c1.date_upload.compareTo(c2.date_upload) }
            else -> throw NotImplementedError("Unknown sorting method")
        }

        val episodes = db.getEpisodes(anime).executeAsBlocking()
            .sortedWith { e1, e2 -> sortFunction(e2, e1) }

        val currEpisodeIndex = episodes.indexOfFirst { episode.id == it.id }
        val episodeNumber = episode.episode_number
        return ((currEpisodeIndex + 1) until episodes.size)
            .map { episodes[it] }
            .firstOrNull {
                it.episode_number < episodeNumber &&
                    it.episode_number >= episodeNumber - 1
            } ?: episode
    }

    companion object {
        fun newIntent(context: Context, anime: Anime, episode: Episode): Intent {
            return Intent(context, WatcherActivity::class.java).apply {
                putExtra("anime", anime)
                putExtra("episode", episode)
                putExtra("second", episode.last_second_seen)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
        const val REQUEST_COOKIES = 1337
    }
}
