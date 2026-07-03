package com.afif.flix

import android.Manifest
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayInputStream
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.afif.flix.databinding.ActivityMainBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity(), BookmarkBottomSheet.BookmarkClickListener {

    private lateinit var binding: ActivityMainBinding
    
    // File chooser callback variables
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val FILE_CHOOSER_REQUEST_CODE = 1001
    private var isAssistiveMenuOpen = false
    private var assistiveDownRawX = 0f
    private var assistiveDownRawY = 0f
    private var assistiveStartX = 0f
    private var assistiveStartY = 0f
    private var didDragAssistiveBall = false

    // Fullscreen video variables
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    // Permission request codes
    private val STORAGE_PERMISSION_CODE = 2002
    private val NOTIFICATION_PERMISSION_CODE = 2003
    
    // Saved download details for resuming after permission grant
    private var pendingDownloadUrl: String? = null
    private var pendingDownloadUserAgent: String? = null
    private var pendingDownloadContentDisposition: String? = null
    private var pendingDownloadMimeType: String? = null

    private val defaultPrimaryUrl = "https://braflix.uk"
    private var primaryUrl = defaultPrimaryUrl
    private var updateDownloadUrl = ""
    private var latestVersionCode = BuildConfig.VERSION_CODE
    private var latestVersionName = BuildConfig.VERSION_NAME
    private var updateChangelog = ""
    private var pendingBraflixSearchQuery: String? = null
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val firestore by lazy { FirebaseFirestore.getInstance() }
    private var syncingBookmarks = false
    private val allowedHosts = mutableSetOf(
    "braflix.uk",
    "https://mediatv.trendingpie.com",
    "https://vidvault.ru",
    "https://video.moviepire.co"
)
    private val blockedAdHosts = mutableSetOf(
        "doubleclick.net",
        "googleadservices.com",
        "googlesyndication.com",
        "google-analytics.com",
        "adservice.google.com",
        "pagead2.googlesyndication.com",
        "adsystem.com",
        "adnxs.com",
        "adsrvr.org",
        "advertising.com",
        "amazon-adsystem.com",
        "popads.net",
        "propellerads.com",
        "propeller-tracking.com",
        "exoclick.com",
        "trafficjunky.net",
        "juicyads.com",
        "hilltopads.net",
        "onclickads.net",
        "popcash.net",
        "adsterra.com",
        "adsterratools.com",
        "highperformanceformat.com",
        "highperformancedisplayformat.com",
        "bet365.com",
        "1xbet.com",
        "bc.game"
    )
    private val blockedUrlParts = mutableListOf(
        "/ads/",
        "/ad/",
        "/advert",
        "/banner",
        "/popunder",
        "/popup",
        "/prebid",
        "/vast",
        "/vpaid",
        "googleads",
        "doubleclick",
        "pagead",
        "popads",
        "propeller",
        "onclick",
        "adsterra"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWebView()
        setupSwipeRefresh()
        setupFloatingControls()
        setupErrorLayouts()
        restoreAssistiveBallPosition()
        setupRemoteConfig()
        requestNotificationPermissionIfNeeded()
        if (!setupAccountState()) return
        registerFcmToken()

        loadInitialPage()
    }

    override fun onResume() {
        super.onResume()
        syncBookmarksFromCloud()
    }

    private fun setupWebView() {
        val settings = binding.webView.settings
        
        // Enabling general web features
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        
        // File selection and uploads support
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = false

        // User Agent adjustment
        settings.userAgentString = settings.userAgentString + " AfifFlix/1.0"

        // Cookie and Session persistence
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(binding.webView, true)

        binding.webView.webViewClient = object : WebViewClient() {
            
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                return handleUrlRouting(url)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url == null) return false
                return handleUrlRouting(url)
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                return if (shouldBlockRequest(url)) emptyBlockedResponse() else null
            }

            @Deprecated("Deprecated in Java")
            override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
                if (url == null) return null
                return if (shouldBlockRequest(url)) emptyBlockedResponse() else null
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Temporarily disable swipe refresh when load starts
                binding.swipeRefresh.isRefreshing = true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.swipeRefresh.isRefreshing = false
                updateBookmarkIcon(url)
                pendingBraflixSearchQuery?.let { tryPerformBraflixSearch(it) }
                
                // Keep SwipeRefreshLayout sync with scroll
                binding.swipeRefresh.isEnabled = (binding.webView.scrollY == 0)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    val errorCode = error?.errorCode ?: 0
                    if (errorCode == ERROR_HOST_LOOKUP || errorCode == ERROR_CONNECT || errorCode == ERROR_TIMEOUT) {
                        showOfflineLayout()
                    } else {
                        showServerErrorLayout()
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                if (errorCode == ERROR_HOST_LOOKUP || errorCode == ERROR_CONNECT || errorCode == ERROR_TIMEOUT) {
                    showOfflineLayout()
                } else {
                    showServerErrorLayout()
                }
            }
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                Toast.makeText(this@MainActivity, "Popup blocked", Toast.LENGTH_SHORT).show()
                return false
            }

            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                result?.confirm()
                return true
            }

            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                result?.cancel()
                return true
            }
            
            // Handle HTML5 fullscreen video requests
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    onHideCustomView()
                    return
                }

                customView = view
                customViewCallback = callback

                // Hide general WebView and controls layout
                binding.swipeRefresh.visibility = View.GONE
                binding.assistiveControlsContainer.visibility = View.GONE
                
                // Populate fullscreen container
                binding.layoutFullscreenContainer.addView(customView)
                binding.layoutFullscreenContainer.visibility = View.VISIBLE

                // Set orientation to auto landscape
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

                // Hide system UI (Immersive Mode)
                hideSystemUI()
            }

            override fun onHideCustomView() {
                if (customView == null) return

                // Remove fullscreen custom view
                binding.layoutFullscreenContainer.removeView(customView)
                binding.layoutFullscreenContainer.visibility = View.GONE
                customView = null

                // Notify custom view callback
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null

                // Restore WebView and controls
                binding.swipeRefresh.visibility = View.VISIBLE
                binding.assistiveControlsContainer.visibility = View.VISIBLE

                // Reset orientation
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

                // Show system UI
                showSystemUI()
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val intent = fileChooserParams?.createIntent()
                if (intent != null) {
                    try {
                        startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE)
                        return true
                    } catch (e: ActivityNotFoundException) {
                        this@MainActivity.filePathCallback?.onReceiveValue(null)
                        this@MainActivity.filePathCallback = null
                        Toast.makeText(this@MainActivity, "File Picker is unavailable", Toast.LENGTH_SHORT).show()
                        return false
                    }
                } else {
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = null
                    return false
                }
            }
        }

        // Web downloads listener
        binding.webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            initiateDownload(url, userAgent, contentDisposition, mimeType)
        }

        // Setup scroll change listener to enable/disable SwipeRefreshLayout at top
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            binding.webView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
                binding.swipeRefresh.isEnabled = (scrollY == 0)
            }
        }
    }

    private fun handleUrlRouting(url: String): Boolean {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()

        if (scheme == "http" || scheme == "https") {
            return !isAllowedHost(host)
        }

        if (scheme == "intent" || scheme == "market" || scheme == "mailto" || scheme == "tel") {
            return true
        }

        return false
    }

    private fun isAllowedHost(host: String?): Boolean {
        if (host == null) return false
        return allowedHosts.any { host == it || host.endsWith(".$it") }
    }

    private fun shouldBlockRequest(url: String): Boolean {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false

        val host = uri.host?.lowercase() ?: return false
        if (blockedAdHosts.any { host == it || host.endsWith(".$it") }) {
            return true
        }

        val normalizedUrl = url.lowercase()
        return blockedUrlParts.any { normalizedUrl.contains(it) }
    }

    private fun emptyBlockedResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            ByteArrayInputStream(ByteArray(0))
        )
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeResources(R.color.accent_red)
        binding.swipeRefresh.setProgressBackgroundColorSchemeResource(R.color.surface_dark)
        binding.swipeRefresh.setOnRefreshListener {
            if (isNetworkAvailable(this)) {
                binding.webView.reload()
            } else {
                binding.swipeRefresh.isRefreshing = false
                showOfflineLayout()
            }
        }
    }

    private fun setupFloatingControls() {
        setupAssistiveBallDrag()

        binding.btnAssistiveBall.setOnClickListener {
            if (didDragAssistiveBall) {
                didDragAssistiveBall = false
                return@setOnClickListener
            }
            setAssistiveMenuOpen(!isAssistiveMenuOpen)
        }

        binding.btnAssistiveSearch.setOnClickListener {
            setAssistiveMenuOpen(false)
            showSearchDialog()
        }

        binding.btnAssistiveBookmark.setOnClickListener {
            setAssistiveMenuOpen(false)
            toggleCurrentBookmark()
        }

        binding.btnAssistiveBookmarks.setOnClickListener {
            setAssistiveMenuOpen(false)
            val bottomSheet = BookmarkBottomSheet()
            bottomSheet.show(supportFragmentManager, "bookmarks")
        }

        binding.btnAssistiveSettings.setOnClickListener {
            setAssistiveMenuOpen(false)
            showSettingsSheet()
        }
    }

    private fun setAssistiveMenuOpen(isOpen: Boolean) {
        isAssistiveMenuOpen = isOpen
        binding.layoutAssistiveMenu.visibility = if (isOpen) View.VISIBLE else View.GONE
    }

    private fun setupAssistiveBallDrag() {
        binding.btnAssistiveBall.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    assistiveDownRawX = event.rawX
                    assistiveDownRawY = event.rawY
                    assistiveStartX = binding.assistiveControlsContainer.x
                    assistiveStartY = binding.assistiveControlsContainer.y
                    didDragAssistiveBall = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - assistiveDownRawX
                    val dy = event.rawY - assistiveDownRawY
                    if (kotlin.math.abs(dx) > 8f || kotlin.math.abs(dy) > 8f) {
                        didDragAssistiveBall = true
                        setAssistiveMenuOpen(false)
                    }
                    moveAssistiveBallTo(assistiveStartX + dx, assistiveStartY + dy)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (didDragAssistiveBall) {
                        saveAssistiveBallPosition()
                    } else {
                        view.performClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun restoreAssistiveBallPosition() {
        binding.assistiveControlsContainer.post {
            val sharedPrefs = getSharedPreferences("AfifFlixSettings", Context.MODE_PRIVATE)
            val savedX = sharedPrefs.getFloat("assistive_x", Float.NaN)
            val savedY = sharedPrefs.getFloat("assistive_y", Float.NaN)
            if (!savedX.isNaN() && !savedY.isNaN()) {
                moveAssistiveBallTo(savedX, savedY)
            }
        }
    }

    private fun moveAssistiveBallTo(targetX: Float, targetY: Float) {
        val parent = binding.root
        val controls = binding.assistiveControlsContainer
        val maxX = (parent.width - controls.width).coerceAtLeast(0).toFloat()
        val maxY = (parent.height - controls.height).coerceAtLeast(0).toFloat()
        controls.x = targetX.coerceIn(0f, maxX)
        controls.y = targetY.coerceIn(0f, maxY)
    }

    private fun saveAssistiveBallPosition() {
        getSharedPreferences("AfifFlixSettings", Context.MODE_PRIVATE)
            .edit()
            .putFloat("assistive_x", binding.assistiveControlsContainer.x)
            .putFloat("assistive_y", binding.assistiveControlsContainer.y)
            .apply()
    }

    private fun showSearchDialog() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_search, null)
        val input = view.findViewById<EditText>(R.id.et_search_query)
        val btnSearch = view.findViewById<View>(R.id.btn_search_submit)

        fun submitSearch() {
            val query = input.text?.toString().orEmpty()
            dialog.dismiss()
            searchBraflix(query)
        }

        btnSearch.setOnClickListener { submitSearch() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                submitSearch()
                true
            } else {
                false
            }
        }

        dialog.setContentView(view)
        dialog.setOnShowListener {
            input.requestFocus()
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
        dialog.show()
    }

    private fun showSettingsSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_settings, null)
        val user = auth.currentUser

        view.findViewById<TextView>(R.id.tv_settings_source).text = getString(
            R.string.current_source_braflix
        )
        view.findViewById<TextView>(R.id.tv_settings_account).text = when {
            user?.displayName.isNullOrBlank() && user?.email.isNullOrBlank() -> getString(R.string.account_guest)
            !user?.displayName.isNullOrBlank() -> user?.displayName.orEmpty()
            else -> user?.email.orEmpty()
        }
        view.findViewById<TextView>(R.id.tv_settings_summary_value).text = when {
            user?.isAnonymous == true -> getString(R.string.account_guest)
            !user?.email.isNullOrBlank() -> user?.email.orEmpty()
            else -> getString(R.string.settings_summary_text)
        }

        view.findViewById<View>(R.id.btn_settings_profile).setOnClickListener {
            dialog.dismiss()
            showProfileSheet()
        }

        view.findViewById<View>(R.id.btn_settings_home).setOnClickListener {
            dialog.dismiss()
            if (isNetworkAvailable(this)) {
                binding.webView.loadUrl(primaryUrl)
            } else {
                showOfflineLayout()
            }
        }

        view.findViewById<View>(R.id.btn_settings_reload).setOnClickListener {
            dialog.dismiss()
            if (isNetworkAvailable(this)) {
                binding.webView.reload()
            } else {
                showOfflineLayout()
            }
        }

        view.findViewById<View>(R.id.btn_settings_update).setOnClickListener {
            dialog.dismiss()
            showUpdateSheet(showUpToDateMessage = true)
        }

        view.findViewById<View>(R.id.btn_settings_clear_cache).setOnClickListener {
            dialog.dismiss()
            binding.webView.clearCache(true)
            CookieManager.getInstance().flush()
            Toast.makeText(this, R.string.cache_cleared, Toast.LENGTH_SHORT).show()
        }

        dialog.setContentView(view)
        dialog.show()
    }

    private fun showProfileSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_profile, null)
        val user = auth.currentUser
        val nameInput = view.findViewById<TextInputEditText>(R.id.et_profile_name)
        val emailValue = view.findViewById<TextView>(R.id.tv_profile_email_value)
        val uidValue = view.findViewById<TextView>(R.id.tv_profile_uid_value)
        val statusValue = view.findViewById<TextView>(R.id.tv_profile_status_value)
        val saveButton = view.findViewById<View>(R.id.btn_profile_save)
        val signOutButton = view.findViewById<View>(R.id.btn_profile_sign_out)

        emailValue.text = user?.email ?: getString(R.string.not_available)
        uidValue.text = user?.uid ?: getString(R.string.not_available)
        statusValue.text = if (user?.isAnonymous == true) getString(R.string.account_guest) else getString(R.string.account_signed_in)
        nameInput.setText(user?.displayName.orEmpty())

        saveButton.setOnClickListener {
            val newName = nameInput.text?.toString().orEmpty().trim()
            if (newName.isBlank()) {
                Toast.makeText(this, R.string.display_name_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            updateDisplayName(newName, dialog)
        }

        signOutButton.setOnClickListener {
            dialog.dismiss()
            signOutAndReturnToAuth()
        }

        view.findViewById<View>(R.id.btn_profile_close).setOnClickListener {
            dialog.dismiss()
        }

        dialog.setContentView(view)
        dialog.show()
    }

    private fun updateDisplayName(newName: String, dialog: BottomSheetDialog) {
        val user = auth.currentUser ?: return
        val profileUpdates = UserProfileChangeRequest.Builder()
            .setDisplayName(newName)
            .build()

        auth.currentUser?.updateProfile(profileUpdates)
            ?.addOnCompleteListener { result ->
                if (!result.isSuccessful) {
                    Toast.makeText(this, result.exception?.message ?: "Unable to update profile", Toast.LENGTH_SHORT).show()
                    return@addOnCompleteListener
                }

                firestore.collection("users")
                    .document(user.uid)
                    .set(
                        mapOf(
                            "displayName" to newName,
                            "email" to (user.email ?: ""),
                            "updatedAt" to System.currentTimeMillis()
                        ),
                        com.google.firebase.firestore.SetOptions.merge()
                    )
                    .addOnSuccessListener {
                        Toast.makeText(this, R.string.profile_saved, Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }
                    .addOnFailureListener { error ->
                        Toast.makeText(this, error.message ?: "Unable to save profile", Toast.LENGTH_SHORT).show()
                    }
            }
    }

    private fun signOutAndReturnToAuth() {
        getSharedPreferences("AfifFlixBookmarks", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
        val googleOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        GoogleSignIn.getClient(this, googleOptions).signOut()
        auth.signOut()
        val intent = Intent(this, AuthActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
        finish()
    }

    private fun showUpdateSheet(showUpToDateMessage: Boolean) {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_update, null)
        val messageView = view.findViewById<TextView>(R.id.tv_update_message)
        val downloadButton = view.findViewById<View>(R.id.btn_update_download)

        if (isUpdateAvailable() && isUsableRemoteUrl(updateDownloadUrl)) {
            messageView.text = getString(
                R.string.update_available,
                latestVersionName,
                updateChangelog.ifBlank { getString(R.string.update_check_title) }
            )
            downloadButton.visibility = View.VISIBLE
            downloadButton.setOnClickListener {
                dialog.dismiss()
                openExternalUrl(updateDownloadUrl)
            }
        } else {
            messageView.text = if (showUpToDateMessage) {
                getString(R.string.app_up_to_date)
            } else {
                getString(R.string.update_not_configured)
            }
            downloadButton.visibility = View.GONE
        }

        view.findViewById<View>(R.id.btn_update_close).setOnClickListener {
            dialog.dismiss()
        }
        dialog.setContentView(view)
        dialog.show()
    }

    private fun openExternalUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to open link", Toast.LENGTH_SHORT).show()
        }
    }

    private fun searchBraflix(query: String) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) return

        if (!isNetworkAvailable(this)) {
            showOfflineLayout()
            return
        }
        pendingBraflixSearchQuery = trimmedQuery

        val currentHost = binding.webView.url?.let { Uri.parse(it).host?.lowercase() }
        val primaryHost = Uri.parse(primaryUrl).host?.lowercase()
        if (currentHost == null || primaryHost == null || currentHost != primaryHost) {
            binding.webView.loadUrl(primaryUrl)
        } else {
            tryPerformBraflixSearch(trimmedQuery)
        }
    }

    private fun tryPerformBraflixSearch(query: String) {
        val escapedQuery = JSONObject.quote(query)
        val script = """
            (function() {
                const query = $escapedQuery;
                const selectors = {
                    icon: [
                        '.top-bar-search',
                        '.top-bar-search i.action',
                        'button[aria-label*="search" i]',
                        'button[class*="search" i]',
                        'a[aria-label*="search" i]',
                        'a[class*="search" i]',
                        'svg.lucide-search'
                    ],
                    input: [
                        '.top-bar-input input',
                        'input[placeholder="Search for a title"]',
                        'input[type="search"]',
                        'input[placeholder*="search" i]',
                        'input[name*="search" i]',
                        'input[class*="search" i]'
                    ],
                    submit: [
                        'form button[type="submit"]',
                        'button[aria-label*="search" i]',
                        'button[class*="search" i]',
                        'form [type="submit"]'
                    ]
                };

                const findElement = (items) => {
                    for (const selector of items) {
                        const element = document.querySelector(selector);
                        if (element) return element;
                    }
                    return null;
                };

                const searchInput = () => {
                    const input = findElement(selectors.input);
                    if (!input) return false;
                    input.focus();
                    input.value = query;
                    input.dispatchEvent(new Event('input', { bubbles: true }));
                    input.dispatchEvent(new Event('change', { bubbles: true }));

                    const form = input.closest('form');
                    const submitButton = (form && form.querySelector('button[type="submit"], [type="submit"]'))
                        || findElement(selectors.submit);

                    if (submitButton) {
                        submitButton.click();
                        return true;
                    }
                    if (form) {
                        form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
                        if (typeof form.requestSubmit === 'function') {
                            form.requestSubmit();
                        } else {
                            form.submit();
                        }
                        return true;
                    }
                    input.dispatchEvent(new InputEvent('input', { bubbles: true, data: query, inputType: 'insertText' }));
                    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', which: 13, keyCode: 13, bubbles: true }));
                    input.dispatchEvent(new KeyboardEvent('keypress', { key: 'Enter', code: 'Enter', which: 13, keyCode: 13, bubbles: true }));
                    input.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', code: 'Enter', which: 13, keyCode: 13, bubbles: true }));
                    return true;
                };

                if (searchInput()) return 'ok';

                const icon = findElement(selectors.icon);
                if (icon) {
                    icon.click();
                    setTimeout(searchInput, 250);
                    setTimeout(searchInput, 600);
                    return 'opened';
                }

                return 'missing';
            })();
        """.trimIndent()

        binding.webView.evaluateJavascript(script) { result ->
            when (result?.trim('"')) {
                "ok", "opened" -> pendingBraflixSearchQuery = null
                "missing" -> {
                    pendingBraflixSearchQuery = null
                    Toast.makeText(this, R.string.search_unavailable, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun toggleCurrentBookmark() {
        val url = binding.webView.url
        val title = binding.webView.title
        if (url != null && title != null) {
            val sharedPrefs = getSharedPreferences("AfifFlixBookmarks", Context.MODE_PRIVATE)
            if (sharedPrefs.contains(url)) {
                sharedPrefs.edit().remove(url).apply()
                Toast.makeText(this, R.string.bookmark_removed, Toast.LENGTH_SHORT).show()
            } else {
                sharedPrefs.edit().putString(url, title).apply()
                Toast.makeText(this, R.string.bookmark_added, Toast.LENGTH_SHORT).show()
            }
            updateBookmarkIcon(url)
            syncBookmarksToCloud()
        }
    }

    private fun setupAccountState(): Boolean {
        val user = auth.currentUser ?: run {
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
            return false
        }
        syncUserProfile(user.uid, user.email.orEmpty(), user.displayName.orEmpty(), user.isAnonymous)
        return true
    }

    private fun syncUserProfile(uid: String, email: String, displayName: String, isAnonymous: Boolean) {
        firestore.collection("users")
            .document(uid)
            .set(
                mapOf(
                    "email" to email,
                    "displayName" to displayName,
                    "isAnonymous" to isAnonymous,
                    "updatedAt" to System.currentTimeMillis()
                ),
                com.google.firebase.firestore.SetOptions.merge()
            )
    }

    private fun registerFcmToken() {
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            val user = auth.currentUser ?: return@addOnSuccessListener
            firestore.collection("users")
                .document(user.uid)
                .collection("private")
                .document("fcm")
                .set(
                    mapOf(
                        "token" to token,
                        "updatedAt" to System.currentTimeMillis()
                    ),
                    com.google.firebase.firestore.SetOptions.merge()
                )
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_CODE
            )
        }
    }

    private fun syncBookmarksFromCloud() {
        if (syncingBookmarks) return
        val user = auth.currentUser ?: return
        syncingBookmarks = true

        firestore.collection("users")
            .document(user.uid)
            .collection("private")
            .document("library")
            .get()
            .addOnSuccessListener { snapshot ->
                val bookmarks = snapshot.get("bookmarks")
                if (bookmarks is List<*>) {
                    val sharedPrefs = getSharedPreferences("AfifFlixBookmarks", Context.MODE_PRIVATE)
                    val editor = sharedPrefs.edit().clear()
                    bookmarks.forEach { item ->
                        val entry = item as? Map<*, *> ?: return@forEach
                        val url = entry["url"] as? String ?: return@forEach
                        val title = entry["title"] as? String ?: return@forEach
                        editor.putString(url, title)
                    }
                    editor.apply()
                    updateBookmarkIcon(binding.webView.url)
                }
            }
            .addOnCompleteListener {
                syncingBookmarks = false
            }
    }

    private fun syncBookmarksToCloud() {
        val user = auth.currentUser ?: return
        val sharedPrefs = getSharedPreferences("AfifFlixBookmarks", Context.MODE_PRIVATE)
        val bookmarks = sharedPrefs.all.mapNotNull { (url, title) ->
            val titleString = title as? String ?: return@mapNotNull null
            mapOf("url" to url, "title" to titleString)
        }

        val payload = mapOf(
            "bookmarks" to bookmarks,
            "updatedAt" to System.currentTimeMillis()
        )

        firestore.collection("users")
            .document(user.uid)
            .collection("private")
            .document("library")
            .set(payload)
    }

    private fun setupRemoteConfig() {
        val remoteConfig = FirebaseRemoteConfig.getInstance()
        val configSettings = FirebaseRemoteConfigSettings.Builder()
            .setMinimumFetchIntervalInSeconds(3600)
            .build()

        val defaults: Map<String, Any> = mapOf(
            "primary_source_url" to defaultPrimaryUrl,
            "fallback_source_urls" to "[]",
            "blocked_ad_hosts" to JSONArray(blockedAdHosts.toList()).toString(),
            "blocked_url_parts" to JSONArray(blockedUrlParts).toString(),
            "latest_version_code" to BuildConfig.VERSION_CODE.toString(),
            "latest_version_name" to BuildConfig.VERSION_NAME,
            "apk_download_url" to "",
            "update_changelog" to "",
            "maintenance_enabled" to false,
            "maintenance_message" to getString(R.string.update_not_configured)
        )
        remoteConfig.setConfigSettingsAsync(configSettings)
        remoteConfig.setDefaultsAsync(defaults)

        remoteConfig.fetchAndActivate().addOnCompleteListener {
            applyRemoteConfig(remoteConfig)
        }
    }

    private fun applyRemoteConfig(remoteConfig: FirebaseRemoteConfig) {
        val remotePrimaryUrl = remoteConfig.getString("primary_source_url")
        if (isUsableRemoteUrl(remotePrimaryUrl)) {
            primaryUrl = remotePrimaryUrl
        }

        val remoteSourceUrls = parseJsonStringArray(remoteConfig.getString("fallback_source_urls"))
        allowedHosts.clear()
        listOf(primaryUrl)
            .plus(remoteSourceUrls.filter(::isUsableRemoteUrl))
            .mapNotNull { Uri.parse(it).host?.lowercase() }
            .forEach { allowedHosts.add(it) }
        if (allowedHosts.isEmpty()) {
            allowedHosts.add("braflix.uk")
        }

        val remoteBlockedHosts = parseJsonStringArray(remoteConfig.getString("blocked_ad_hosts"))
        if (remoteBlockedHosts.isNotEmpty()) {
            blockedAdHosts.addAll(remoteBlockedHosts.map { it.lowercase() })
        }

        val remoteBlockedParts = parseJsonStringArray(remoteConfig.getString("blocked_url_parts"))
        if (remoteBlockedParts.isNotEmpty()) {
            blockedUrlParts.addAll(remoteBlockedParts.map { it.lowercase() })
        }

        latestVersionCode = remoteConfig.getLong("latest_version_code").toInt()
        latestVersionName = remoteConfig.getString("latest_version_name").ifBlank { BuildConfig.VERSION_NAME }
        updateDownloadUrl = remoteConfig.getString("apk_download_url")
        updateChangelog = remoteConfig.getString("update_changelog")

        if (remoteConfig.getBoolean("maintenance_enabled")) {
            showMaintenanceMessage(remoteConfig.getString("maintenance_message"))
        }

        if (isUpdateAvailable() && isUsableRemoteUrl(updateDownloadUrl)) {
            showUpdateSheet(showUpToDateMessage = false)
        }
    }

    private fun parseJsonStringArray(rawValue: String): List<String> {
        return try {
            val jsonArray = JSONArray(rawValue)
            List(jsonArray.length()) { index -> jsonArray.optString(index) }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun isUsableRemoteUrl(url: String): Boolean {
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase() ?: return false
        val scheme = uri.scheme?.lowercase()
        return (scheme == "https" || scheme == "http") &&
            host != "example.com" &&
            !host.endsWith(".example.com")
    }

    private fun isUpdateAvailable(): Boolean {
        return latestVersionCode > BuildConfig.VERSION_CODE
    }

    private fun showMaintenanceMessage(message: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.maintenance_title)
            .setMessage(message.ifBlank { getString(R.string.maintenance_title) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun setupErrorLayouts() {
        binding.btnRetryOffline.setOnClickListener {
            binding.layoutOfflineError.visibility = View.GONE
            loadInitialPage()
        }

        binding.btnRetryServer.setOnClickListener {
            binding.layoutServerError.visibility = View.GONE
            loadInitialPage()
        }
    }

    private fun loadInitialPage() {
        if (isNetworkAvailable(this)) {
            val currentUrl = binding.webView.url
            if (currentUrl.isNullOrEmpty()) {
                binding.webView.loadUrl(primaryUrl)
            } else {
                binding.webView.reload()
            }
        } else {
            showOfflineLayout()
        }
    }

    private fun showOfflineLayout() {
        binding.swipeRefresh.isRefreshing = false
        binding.layoutOfflineError.visibility = View.VISIBLE
    }

    private fun showServerErrorLayout() {
        binding.swipeRefresh.isRefreshing = false
        binding.layoutServerError.visibility = View.VISIBLE
    }

    private fun updateBookmarkIcon(url: String?) {
        if (url == null) return
        val sharedPrefs = getSharedPreferences("AfifFlixBookmarks", Context.MODE_PRIVATE)
        if (sharedPrefs.contains(url)) {
            binding.btnAssistiveBookmark.setImageResource(R.drawable.ic_star_filled)
            binding.btnAssistiveBookmark.imageTintList = ContextCompat.getColorStateList(this, R.color.accent_red)
        } else {
            binding.btnAssistiveBookmark.setImageResource(R.drawable.ic_star_outline)
            binding.btnAssistiveBookmark.imageTintList = ContextCompat.getColorStateList(this, R.color.white)
        }
    }

    // Handles Bookmarks interface clicks
    override fun onBookmarkClicked(url: String) {
        if (isNetworkAvailable(this)) {
            binding.webView.loadUrl(url)
        } else {
            showOfflineLayout()
        }
    }

    override fun onBookmarkDeleted(url: String) {
        val currentUrl = binding.webView.url
        if (url.isEmpty() || currentUrl == url) {
            updateBookmarkIcon(currentUrl)
        }
    }

    // File Chooser Intent result handler
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (filePathCallback == null) return
            val results = WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
        }
    }

    // Downloads handler
    private fun initiateDownload(url: String, userAgent: String, contentDisposition: String, mimeType: String) {
        // Scoped storage check: WRITE_EXTERNAL_STORAGE is ONLY required on Android 9 and below
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                
                // Cache download info to resume after user approval
                pendingDownloadUrl = url
                pendingDownloadUserAgent = userAgent
                pendingDownloadContentDisposition = contentDisposition
                pendingDownloadMimeType = mimeType

                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    STORAGE_PERMISSION_CODE
                )
                return
            }
        }
        
        executeDownload(url, userAgent, contentDisposition, mimeType)
    }

    private fun executeDownload(url: String, userAgent: String, contentDisposition: String, mimeType: String) {
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                addRequestHeader("User-Agent", userAgent)
                addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url))
                setDescription("Downloading file…")
                setTitle(fileName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            Toast.makeText(this, R.string.error_download_started, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Resume download if permissions were approved
                val url = pendingDownloadUrl
                val ua = pendingDownloadUserAgent
                val cd = pendingDownloadContentDisposition
                val mt = pendingDownloadMimeType
                if (url != null && ua != null && cd != null && mt != null) {
                    executeDownload(url, ua, cd, mt)
                }
            } else {
                Toast.makeText(this, "Download failed: storage permission denied", Toast.LENGTH_LONG).show()
            }
            // Clear pending download caches
            pendingDownloadUrl = null
            pendingDownloadUserAgent = null
            pendingDownloadContentDisposition = null
            pendingDownloadMimeType = null
        }
    }

    // Android navigation controls
    override fun onBackPressed() {
        if (customView != null) {
            // Exit fullscreen video playback if back is pressed
            binding.webView.webChromeClient?.onHideCustomView()
        } else if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    // Fullscreen styling and layouts
    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun showSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork)
        return capabilities != null && (
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        )
    }

    override fun onPause() {
        super.onPause()
        // Ensure cookies are written to persistent storage on app exit/pause
        CookieManager.getInstance().flush()
        syncBookmarksToCloud()
    }
}
