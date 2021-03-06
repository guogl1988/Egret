package com.geckour.egret.view.activity

import android.content.*
import android.databinding.DataBindingUtil
import android.graphics.Color
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.support.design.widget.Snackbar
import android.support.v4.app.ShareCompat
import android.support.v4.content.ContextCompat
import android.support.v7.widget.SearchView
import android.text.Html
import android.text.TextUtils
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.geckour.egret.App.Companion.STATE_KEY_CATEGORY
import com.geckour.egret.R
import com.geckour.egret.api.MastodonClient
import com.geckour.egret.databinding.ActivityMainBinding
import com.geckour.egret.model.MuteClient
import com.geckour.egret.model.MuteInstance
import com.geckour.egret.util.Common
import com.geckour.egret.util.Common.Companion.hideSoftKeyBoard
import com.geckour.egret.util.OrmaProvider
import com.geckour.egret.view.adapter.TimelineAdapter
import com.geckour.egret.view.adapter.model.TimelineContent
import com.geckour.egret.view.fragment.*
import com.mikepenz.materialdrawer.AccountHeader
import com.mikepenz.materialdrawer.AccountHeaderBuilder
import com.mikepenz.materialdrawer.Drawer
import com.mikepenz.materialdrawer.DrawerBuilder
import com.mikepenz.materialdrawer.model.DividerDrawerItem
import com.mikepenz.materialdrawer.model.PrimaryDrawerItem
import com.mikepenz.materialdrawer.model.ProfileDrawerItem
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import timber.log.Timber

class MainActivity : BaseActivity(), ListDialogFragment.OnItemClickListener {

    lateinit var binding: ActivityMainBinding
    lateinit private var drawer: Drawer
    lateinit private var accountHeader: AccountHeader
    private val sharedPref: SharedPreferences by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    lateinit private var currentCategory: TimelineFragment.Category

    companion object {
        const val STATE_KEY_THEME_MODE = "stateKeyThemeMode"
        const val ARGS_KEY_CATEGORY = "argsKeyCategory"
        const val REQUEST_CODE_NOTIFICATION = 0

        fun getIntent(context: Context, category: TimelineFragment.Category? = null): Intent {
            val intent = Intent(context, MainActivity::class.java)
            category?.let { intent.putExtra(ARGS_KEY_CATEGORY, it) }

            return intent
        }
    }

    enum class NavItem {
        NAV_ITEM_LOGIN,
        NAV_ITEM_TL_PUBLIC,
        NAV_ITEM_TL_LOCAL,
        NAV_ITEM_TL_USER,
        NAV_ITEM_TL_NOTIFICATION,
        NAV_ITEM_SETTINGS,
        NAV_ITEM_OTHERS
    }

    interface OnBackPressedListener {
        fun onBackPressedInMainActivity(callback: (doBack: Boolean) -> Any)
    }

    val timelineListener = object: TimelineAdapter.Callbacks {
        override val copyTootUrlToClipboard = { url: String ->
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("url of toot", url)
            clipboard.primaryClip = clip
        }

        override val shareToot = { content: TimelineContent.TimelineStatus ->
            ShareCompat.IntentBuilder.from(this@MainActivity).apply {
                setChooserTitle(R.string.dialog_title_share_toot)
                setSubject(getString(R.string.dialog_subject_share_toot))
                setText("${content.nameStrong}(${content.nameWeak}):\n${content.body}")
                setType("text/plain")
            }.startChooser()
        }

        override val showTootInBrowser = { content: TimelineContent.TimelineStatus ->
            val uri = Uri.parse(content.tootUrl)
            if (Common.isModeDefaultBrowser(this@MainActivity)) {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } else {
                Common.getCustomTabsIntent(this@MainActivity).launchUrl(this@MainActivity, uri)
            }
        }

        override val copyTootToClipboard = { content: TimelineContent.TimelineStatus ->
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("toot", content.body.toString())
            clipboard.primaryClip = clip
        }

        override val showMuteDialog = { content: TimelineContent.TimelineStatus ->
            val itemStrings = resources.getStringArray(R.array.mute_from_toot).toList()
            val items = itemStrings.mapIndexed { i, s ->
                when (i) {
                    0 -> Pair(R.string.array_item_mute_account, s.format(content.nameWeak))
                    1 -> Pair(
                            R.string.array_item_mute_keyword,
                            s.format(
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                        Html.fromHtml(content.body.toString(), Html.FROM_HTML_MODE_COMPACT).toString()
                                    } else {
                                        Html.fromHtml(content.body.toString()).toString()
                                    }
                            )
                    )
                    2 -> Pair(R.string.array_item_mute_hash_tag, if (content.tags.isEmpty()) "" else s.format(content.tags.joinToString { tag -> "#$tag" }))
                    3 -> {
                        var instance = content.nameWeak.replace(Regex("^@.+@(.+)$"), "@$1")

                        if (content.nameWeak == instance) {
                            instance = ""
                            Common.getCurrentAccessToken()?.instanceId?.let {
                                instance = "@${OrmaProvider.db.selectFromInstanceAuthInfo().idEq(it).last().instance}"
                            }
                        }
                        Pair(R.string.array_item_mute_instance, s.format(instance))
                    }
                    4 -> Pair(R.string.array_item_mute_client, if (TextUtils.isEmpty(content.app)) "" else s.format(content.app))
                    else -> Pair(-1, s)
                }
            }.filter { !TextUtils.isEmpty(it.second) }
            items.forEach { Timber.d("items: ${it.first}, ${it.second}") }
            ListDialogFragment.newInstance(
                    getString(R.string.dialog_title_mute),
                    items,
                    content
            ).show(supportFragmentManager, ListDialogFragment.TAG)
        }

        override val showProfile = { accountId: Long ->
            Common.resetAuthInfo()?.let {
                MastodonClient(it).getAccount(accountId)
                        .subscribeOn(Schedulers.newThread())
                        .observeOn(AndroidSchedulers.mainThread())
                        .compose(bindToLifecycle())
                        .subscribe({ account ->
                            val fragment = AccountProfileFragment.newInstance(account)

                            supportFragmentManager.beginTransaction()
                                    .replace(R.id.container, fragment, AccountProfileFragment.TAG)
                                    .addToBackStack(AccountProfileFragment.TAG)
                                    .commit()
                        })
            } ?: let {}
        }

        override val onReply = { content: TimelineContent.TimelineStatus ->
            replyStatusById(content)
        }

        override val onFavStatus = { content: TimelineContent.TimelineStatus, view: ImageView ->
            favStatusById(content, view)
        }

        override val onBoostStatus = { content: TimelineContent.TimelineStatus, view: ImageView ->
            boostStatusById(content, view)
        }

        override val onClickMedia = { urls: List<String>, position: Int ->
            val fragment = ShowImagesDialogFragment.newInstance(urls, position)
            supportFragmentManager.beginTransaction()
                    .add(fragment, ShowImagesDialogFragment.TAG)
                    .addToBackStack(ShowImagesDialogFragment.TAG)
                    .commit()
        }

        override val showTootDetail = { statusId: Long ->
            val fragment = ShowTootDetailFragment.newInstance(statusId)
            supportFragmentManager.beginTransaction()
                    .replace(R.id.container, fragment, ShowTootDetailFragment.TAG)
                    .addToBackStack(ShowTootDetailFragment.TAG)
                    .commit()
        }

        override val showHashTagTimeline = { hashTag: String ->
            showTimelineFragment(TimelineFragment.Category.HashTag, hashTag = hashTag)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setTheme(if (isModeDark()) R.style.AppTheme_Dark_NoActionBar else R.style.AppTheme_NoActionBar)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_main)
        setSupportActionBar(binding.appBarMain.toolbar)

        // NavDrawer内のアカウント情報表示部
        accountHeader = getAccountHeader()

        setNavDrawer()
        commitAccountsIntoAccountHeader()

        binding.appBarMain.contentMain.fab.setOnClickListener { showCreateNewTootFragment() }
    }

    override fun onResume() {
        super.onResume()

        if (sharedPref.contains(STATE_KEY_THEME_MODE)) {
            if (sharedPref.getBoolean(STATE_KEY_THEME_MODE, false) != isModeDark()) {
                recreate().apply {
                    sharedPref.edit().remove(STATE_KEY_THEME_MODE).apply()
                }
            }

            sharedPref.edit().remove(STATE_KEY_THEME_MODE).apply()
        }

        currentCategory =
                when {
                    intent.extras?.containsKey(ARGS_KEY_CATEGORY) == true -> intent.extras[ARGS_KEY_CATEGORY] as TimelineFragment.Category
                    sharedPref.contains(STATE_KEY_CATEGORY) -> TimelineFragment.Category.values()[sharedPref.getInt(STATE_KEY_CATEGORY, TimelineFragment.Category.Public.ordinal)]
                    else -> TimelineFragment.Category.Public
                }

        binding.appBarMain.contentMain.apply {
            simplicityTootBody.setOnKeyListener { v, keyCode, event ->
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                (v as EditText).let {
                                    if (it.selectionStart == 0 && it.selectionStart == it.selectionEnd) {
                                        it.requestFocusFromTouch()
                                        it.requestFocus()
                                        it.setSelection(0)
                                        true
                                    } else false
                                }
                            }

                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                (v as EditText).let {
                                    if (it.selectionEnd == it.text.length && it.selectionStart == it.selectionEnd) {
                                        it.requestFocusFromTouch()
                                        it.requestFocus()
                                        it.setSelection(it.text.length)
                                        true
                                    } else false
                                }
                            }

                            else -> false
                        }
                    }

                    else -> false
                }
            }

            if (simplicityTootBody.text.isEmpty()) {
                simplicityTootBody.clearFocus()
                hideSoftKeyBoard(simplicityTootBody)
            }
        }
    }

    override fun onPause() {
        super.onPause()

        sharedPref.edit()
                .putBoolean(STATE_KEY_THEME_MODE, isModeDark())
                .apply()
    }

    override fun onBackPressed() {
        if (drawer.isDrawerOpen) {
            drawer.closeDrawer()
        } else {
            (supportFragmentManager.fragments.lastOrNull { it?.isVisible ?: false } as? OnBackPressedListener)?.let {
                it.onBackPressedInMainActivity { if (it) super.onBackPressed() }
            } ?: super.onBackPressed()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        menu?.findItem(R.id.action_search)?.icon?.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_ATOP)
        (menu?.findItem(R.id.action_search)?.actionView as SearchView?)?.setOnQueryTextListener(object: SearchView.OnQueryTextListener {
            override fun onQueryTextChange(text: String?): Boolean = false

            override fun onQueryTextSubmit(text: String?): Boolean {
                if (text != null) {
                    showSearchResult(text)
                    return true
                }
                return false
            }
        })

        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.

        when (item.itemId) {}

        return super.onOptionsItemSelected(item)
    }

    fun showSearchResult(query: String) {
        Common.resetAuthInfo()?.let {
            MastodonClient(it).search(query)
                    .subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .compose(bindToLifecycle())
                    .subscribe({ result ->
                        Log.d("showSearchResult", "result.hashTags: ${result.hashTags}")

                        val fragment = SearchResultFragment.newInstance(query = query, result = result)
                        supportFragmentManager.beginTransaction()
                                .replace(R.id.container, fragment, SearchResultFragment.TAG)
                                .addToBackStack(SearchResultFragment.TAG)
                                .commit()
                    }, Throwable::printStackTrace)
        }
    }

    override fun onClickListDialogItem(resId: Int, content: TimelineContent.TimelineStatus) {
        when (resId) {
            R.string.array_item_mute_account -> {
                Common.resetAuthInfo()?.let { domain ->
                    MastodonClient(domain).getOwnAccount()
                            .flatMap { if (it.id == content.accountId) Single.never() else MastodonClient(domain).muteAccount(content.accountId) }
                            .subscribeOn(Schedulers.newThread())
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe({
                                Snackbar.make(binding.root, "Muted account: ${content.nameWeak}", Snackbar.LENGTH_SHORT).show()
                            }, Throwable::printStackTrace)
                }
            }

            R.string.array_item_mute_keyword -> {
                val fragment = KeywordMuteFragment.newInstance(content.body.toString())
                supportFragmentManager.beginTransaction()
                        .replace(R.id.container, fragment, KeywordMuteFragment.TAG)
                        .addToBackStack(KeywordMuteFragment.TAG)
                        .commit()
            }

            R.string.array_item_mute_hash_tag -> {
                val fragment = HashTagMuteFragment.newInstance(content.tags)
                supportFragmentManager.beginTransaction()
                        .replace(R.id.container, fragment, HashTagMuteFragment.TAG)
                        .addToBackStack(HashTagMuteFragment.TAG)
                        .commit()
            }

            R.string.array_item_mute_instance -> {
                var instance = content.nameWeak.replace(Regex("^@.+@(.+)$"), "@$1")

                if (content.nameWeak == instance) {
                    instance = ""
                    Common.getCurrentAccessToken()?.instanceId?.let {
                        instance = "@${OrmaProvider.db.selectFromInstanceAuthInfo().idEq(it).last().instance}"
                    }
                }

                if (!TextUtils.isEmpty(instance)) {
                    OrmaProvider.db.prepareInsertIntoMuteInstanceAsSingle()
                            .map { inserter -> inserter.execute(MuteInstance(-1L, instance)) }
                            .subscribeOn(Schedulers.newThread())
                            .observeOn(AndroidSchedulers.mainThread())
                            .compose(bindToLifecycle())
                            .subscribe({
                                Snackbar.make(binding.root, "Muted instance: $instance", Snackbar.LENGTH_SHORT).show()
                            }, Throwable::printStackTrace)
                }
            }

            R.string.array_item_mute_client -> {
                content.app?.let {
                    OrmaProvider.db.prepareInsertIntoMuteClientAsSingle()
                            .map { inserter -> inserter.execute(MuteClient(-1L, it)) }
                            .subscribeOn(Schedulers.newThread())
                            .observeOn(AndroidSchedulers.mainThread())
                            .compose(bindToLifecycle())
                            .subscribe({
                                Snackbar.make(binding.root, "Muted client: ${content.app}", Snackbar.LENGTH_SHORT).show()
                            }, Throwable::printStackTrace)
                }
            }
        }
    }

    fun commitAccountsIntoAccountHeader() {
        accountHeader.clear()

        Observable.fromIterable(OrmaProvider.db.selectFromAccessToken())
                .flatMap {
                    MastodonClient(Common.setAuthInfo(it) ?: throw IllegalArgumentException()).getOwnAccount()
                            .map { account -> Pair(it, account) }
                            .toObservable()
                }
                .flatMap { (token, account) ->
                    (if (account.avatarUrl.startsWith("http")) Glide.with(this).load(account.avatarUrl).submit().get() else null)
                            .let {
                                Observable.just(it)
                                        .map { drawable -> Pair(Pair(token, account), drawable) }
                            }
                }
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .compose(bindToLifecycle())
                .subscribe({ (pair, drawable) ->
                    val domain = OrmaProvider.db.selectFromInstanceAuthInfo().idEq(pair.first.instanceId).last().instance
                    val item = ProfileDrawerItem()
                            .withName(pair.second.displayName)
                            .withEmail("@${pair.second.username}@$domain")
                            .withIdentifier(pair.second.id)
                            .withIcon(drawable)
                    accountHeader.addProfiles(item)
                }, Throwable::printStackTrace, {
                    val currentAccessToken = Common.getCurrentAccessToken()
                    if (currentAccessToken == null) {
                        Single.just(OrmaProvider.db.selectFromAccessToken().last())
                                .flatMap { token ->
                                    OrmaProvider.db.updateAccessToken()
                                            .idEq(token.id)
                                            .isCurrent(true)
                                            .executeAsSingle()
                                            .map { token }
                                }
                                .subscribeOn(Schedulers.newThread())
                                .observeOn(AndroidSchedulers.mainThread())
                                .subscribe({
                                    Timber.d("updated access token: ${it.token}")
                                    accountHeader.setActiveProfile(it.accountId)
                                }, Throwable::printStackTrace)
                    } else {
                        accountHeader.setActiveProfile(currentAccessToken.accountId)
                    }
                    supportActionBar?.setDisplayShowHomeEnabled(true)

                    showTimelineFragment()
                })
    }

    fun getAccountHeader(): AccountHeader =
            AccountHeaderBuilder().withActivity(this)
                    .withHeaderBackground(R.drawable.side_nav_bar)
                    .withOnAccountHeaderListener { v, profile, current ->
                        if (v.id == R.id.material_drawer_account_header_current) {
                            Common.resetAuthInfo()?.let {
                                MastodonClient(it).getOwnAccount()
                                        .subscribeOn(Schedulers.newThread())
                                        .observeOn(AndroidSchedulers.mainThread())
                                        .compose(bindToLifecycle())
                                        .subscribe({ account ->
                                            val fragment = AccountProfileFragment.newInstance(account)
                                            supportFragmentManager.beginTransaction()
                                                    .replace(R.id.container, fragment, AccountProfileFragment.TAG)
                                                    .addToBackStack(AccountProfileFragment.TAG)
                                                    .commit()
                                        }, Throwable::printStackTrace)
                            }
                            return@withOnAccountHeaderListener false
                        } else if (!current) {
                            supportFragmentManager.fragments.lastOrNull { it.isVisible }?.let {
                                (it as? TimelineFragment)?.let {
                                    supportFragmentManager.beginTransaction().detach(it).commit()
                                    supportFragmentManager.executePendingTransactions()
                                }
                                resetAccount(profile.identifier)
                            }

                            return@withOnAccountHeaderListener false
                        }

                        return@withOnAccountHeaderListener true
                    }
                    .build()

    fun resetAccount(identifier: Long): Disposable = OrmaProvider.db.updateAccessToken().isCurrentEq(true).isCurrent(false).executeAsSingle()
            .flatMap { OrmaProvider.db.updateAccessToken().accountIdEq(identifier).isCurrent(true).executeAsSingle() }
            .subscribeOn(Schedulers.newThread())
            .observeOn(AndroidSchedulers.mainThread())
            .compose(bindToLifecycle())
            .subscribe({ i ->
                Timber.d("updated row count: $i")

                showTimelineFragment(force = true)
            }, Throwable::printStackTrace)

    fun setNavDrawer() {
        drawer = DrawerBuilder().withActivity(this)
                .withTranslucentStatusBar(false)
                .withActionBarDrawerToggleAnimated(true)
                .withToolbar(binding.appBarMain.toolbar)
                .addDrawerItems(
                        PrimaryDrawerItem().withName(R.string.navigation_drawer_item_tl_public).withIdentifier(NavItem.NAV_ITEM_TL_PUBLIC.ordinal.toLong()).withIcon(R.drawable.ic_public_black_24px).withIconTintingEnabled(true).withIconColorRes(R.color.icon_tint_dark),
                        PrimaryDrawerItem().withName(R.string.navigation_drawer_item_tl_local).withIdentifier(NavItem.NAV_ITEM_TL_LOCAL.ordinal.toLong()).withIcon(R.drawable.ic_place_black_24px).withIconTintingEnabled(true).withIconColorRes(R.color.icon_tint_dark),
                        PrimaryDrawerItem().withName(R.string.navigation_drawer_item_tl_user).withIdentifier(NavItem.NAV_ITEM_TL_USER.ordinal.toLong()).withIcon(R.drawable.ic_mood_black_24px).withIconTintingEnabled(true).withIconColorRes(R.color.icon_tint_dark),
                        PrimaryDrawerItem().withName(R.string.navigation_drawer_item_tl_notification).withIdentifier(NavItem.NAV_ITEM_TL_NOTIFICATION.ordinal.toLong()).withIcon(R.drawable.ic_notifications_black_24px).withIconTintingEnabled(true).withIconColorRes(R.color.icon_tint_dark),
                        DividerDrawerItem(),
                        PrimaryDrawerItem().withName(R.string.navigation_drawer_item_login).withIdentifier(NavItem.NAV_ITEM_LOGIN.ordinal.toLong()).withIcon(R.drawable.ic_person_add_black_24px).withIconTintingEnabled(true).withIconColorRes(R.color.icon_tint_dark),
                        DividerDrawerItem(),
                        PrimaryDrawerItem().withName(R.string.navigation_drawer_item_settings).withIdentifier(NavItem.NAV_ITEM_SETTINGS.ordinal.toLong()).withIcon(R.drawable.ic_settings_black_24px).withIconTintingEnabled(true).withIconColorRes(R.color.icon_tint_dark),
                        PrimaryDrawerItem().withName(R.string.navigation_drawer_item_others).withIdentifier(NavItem.NAV_ITEM_OTHERS.ordinal.toLong()).withIcon(R.drawable.ic_extension_black_24px).withIconTintingEnabled(true).withIconColorRes(R.color.icon_tint_dark)
                )
                .withOnDrawerItemClickListener { _, _, item ->
                    return@withOnDrawerItemClickListener when (item.identifier) {
                        NavItem.NAV_ITEM_LOGIN.ordinal.toLong() -> {
                            startActivity(LoginActivity.getIntent(this))
                            false
                        }

                        NavItem.NAV_ITEM_TL_PUBLIC.ordinal.toLong() -> {
                            showTimelineFragment(TimelineFragment.Category.Public)
                            false
                        }

                        NavItem.NAV_ITEM_TL_LOCAL.ordinal.toLong() -> {
                            showTimelineFragment(TimelineFragment.Category.Local)
                            false
                        }

                        NavItem.NAV_ITEM_TL_USER.ordinal.toLong() -> {
                            showTimelineFragment(TimelineFragment.Category.User)
                            false
                        }

                        NavItem.NAV_ITEM_TL_NOTIFICATION.ordinal.toLong() -> {
                            showTimelineFragment(TimelineFragment.Category.Notification)
                            false
                        }

                        NavItem.NAV_ITEM_SETTINGS.ordinal.toLong() -> {
                            val intent = SettingActivity.getIntent(this, SettingActivity.Type.Preference)
                            startActivity(intent)
                            false
                        }

                        NavItem.NAV_ITEM_OTHERS.ordinal.toLong() -> {
                            val intent = SettingActivity.getIntent(this, SettingActivity.Type.Misc)
                            startActivity(intent)
                            false
                        }

                        else -> true
                    }
                }
                .withAccountHeader(accountHeader)
                .build()
    }

    fun showTimelineFragment(category: TimelineFragment.Category = currentCategory, force: Boolean = false, hashTag: String? = null) {
        val currentFragment = supportFragmentManager.fragments?.lastOrNull { it?.isVisible ?: false }
        val reqFragment = supportFragmentManager.findFragmentByTag(category.name)

        if (!force
                && currentFragment != null
                && currentFragment.isVisible
                && currentFragment.tag == category.name) return // 要求されたカテゴリを現在表示している場合は早期return

        supportFragmentManager.beginTransaction()
                .apply {
                    currentFragment?.let { detach(it) }
                    reqFragment?.let {
                        attach(it)
                    } ?: let {
                        val fragment = TimelineFragment.newInstance(category, hashTag)
                        replace(R.id.container, fragment, category.name)
                    }
                    if (supportFragmentManager.fragments?.size ?: 0 > 1) addToBackStack(category.name)
                }
                .commit()

        currentCategory = category
    }

    fun resetSelectionNavItem(identifier: Long) {
        if (identifier < 0) drawer.apply {
            getDrawerItem(drawer.currentSelection)?.let {
                it.withSetSelected(false)
                updateItem(it)
            }
        }
        else if (drawer.currentSelection != identifier) drawer.setSelection(identifier)
    }

    fun showCreateNewTootFragment() {
        val token = Common.getCurrentAccessToken() ?: return
        val fragment = NewTootCreateFragment.newInstance(token.id)
        supportFragmentManager.beginTransaction()
                .replace(R.id.container, fragment, NewTootCreateFragment.TAG)
                .addToBackStack(NewTootCreateFragment.TAG)
                .commit()
    }

    fun replyStatusById(content: TimelineContent.TimelineStatus) {
        val fragment = NewTootCreateFragment.newInstance(
                Common.getCurrentAccessToken()?.id ?: return,
                replyToStatusId = content.id,
                replyToAccountName = content.nameWeak)

        supportFragmentManager.beginTransaction()
                .replace(R.id.container, fragment, NewTootCreateFragment.TAG)
                .addToBackStack(NewTootCreateFragment.TAG)
                .commit()
    }

    fun favStatusById(content: TimelineContent.TimelineStatus, view: ImageView) {
        val statusId = content.rebloggedStatusContent?.id ?: content.id
        val domain = Common.resetAuthInfo() ?: return
        MastodonClient(domain).getStatusByStatusId(statusId)
                .flatMap { status ->
                    if (status.favourited) MastodonClient(domain).unFavoriteByStatusId(statusId)
                    else MastodonClient(domain).favoriteByStatusId(statusId)
                }
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .compose(bindToLifecycle())
                .subscribe( { status ->
                    if (content.rebloggedStatusContent == null) content.favourited = status.favourited else content.rebloggedStatusContent?.favourited = status.reblog?.favourited ?: false
                    view.setColorFilter(ContextCompat.getColor(this, if (status.favourited) R.color.colorAccent else R.color.icon_tint_dark))
                }, Throwable::printStackTrace)
    }

    fun boostStatusById(content: TimelineContent.TimelineStatus, view: ImageView) {
        val statusId = content.rebloggedStatusContent?.id ?: content.id
        val domain = Common.resetAuthInfo() ?: return
        MastodonClient(domain).getStatusByStatusId(statusId)
                .flatMap { status ->
                    if (status.reblogged) MastodonClient(domain).unReblogByStatusId(statusId)
                    else MastodonClient(domain).reblogByStatusId(statusId)
                }
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .compose(bindToLifecycle())
                .subscribe( { status ->
                    if (content.rebloggedStatusContent == null) content.reblogged = status.reblogged else content.rebloggedStatusContent?.reblogged = status.reblog?.reblogged ?: false
                    view.setColorFilter(ContextCompat.getColor(this, if (status.reblogged) R.color.colorAccent else R.color.icon_tint_dark))
                }, Throwable::printStackTrace)
    }
}
