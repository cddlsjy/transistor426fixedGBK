/*
 * PlayerFragment.kt
 * Implements the PlayerFragment abstract class
 * PlayerFragment is the fragment that hosts Transistor's list of stations and a player sheet
 *
 * This file is part of
 * TRANSISTOR - Radio App for Android
 *
 * Copyright (c) 2015-25 - Y20K.org
 * Licensed under the MIT-License
 * http://opensource.org/licenses/MIT
 */


package org.y20k.transistor

import android.Manifest
import android.app.Activity
import android.app.Activity.RESULT_OK
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.isGone
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.y20k.transistor.collection.CollectionAdapter
import org.y20k.transistor.collection.CollectionViewModel
import org.y20k.transistor.core.Collection
import org.y20k.transistor.core.Station
import org.y20k.transistor.dialogs.AddStationDialog
import org.y20k.transistor.dialogs.FindStationDialog
import org.y20k.transistor.dialogs.YesNoDialog
import org.y20k.transistor.extensions.cancelSleepTimer
import org.y20k.transistor.extensions.play
import org.y20k.transistor.extensions.playStreamDirectly
import org.y20k.transistor.extensions.requestMetadataHistory
import org.y20k.transistor.extensions.requestSleepTimerRemaining
import org.y20k.transistor.extensions.requestSleepTimerRunning
import org.y20k.transistor.extensions.startSleepTimer
import org.y20k.transistor.helpers.BackupHelper
import org.y20k.transistor.helpers.CollectionHelper
import org.y20k.transistor.helpers.DownloadHelper
import org.y20k.transistor.helpers.FileHelper
import org.y20k.transistor.helpers.NetworkHelper
import org.y20k.transistor.helpers.PreferencesHelper
import org.y20k.transistor.helpers.UiHelper
import org.y20k.transistor.helpers.UpdateHelper
import org.y20k.transistor.ui.LayoutHolder
import org.y20k.transistor.ui.PlayerState


/*
 * BasePlayerFragment class
 */
abstract class BasePlayerFragment: Fragment(),
    SharedPreferences.OnSharedPreferenceChangeListener,
    FindStationDialog.FindStationDialogListener,
    AddStationDialog.AddStationDialogListener,
    CollectionAdapter.CollectionAdapterListener,
    YesNoDialog.YesNoDialogListener,
    View.OnKeyListener {

    /* Define log tag */
    private val TAG: String = PlayerFragment::class.java.simpleName


    /* Main class variables */
    lateinit var layout: LayoutHolder
    private lateinit var collectionViewModel: CollectionViewModel
    private lateinit var collectionAdapter: CollectionAdapter
    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private val controller: MediaController? get() = if (controllerFuture.isDone) controllerFuture.get() else null // defines the Getter for the MediaController
    private var collection: Collection = Collection()
    private var playerState: PlayerState = PlayerState()
    private var listLayoutState: Parcelable? = null
    private val handler: Handler = Handler(Looper.getMainLooper())
    private var tempStationUuid: String = String()
    private var remoteControlEnabled: Boolean = false


    /* Overrides onCreate from Fragment */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // handle back tap/gesture
        requireActivity().onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // minimize player sheet - or if already minimized let activity handle back
                    if (isEnabled && this@BasePlayerFragment::layout.isInitialized && !layout.minimizePlayerIfExpanded()) {
                        isEnabled = false
                        activity?.onBackPressed()
                    }
                }
            })

        // load player state
        playerState = PreferencesHelper.loadPlayerState()

        // create view model and observe changes in collection view model
        collectionViewModel = ViewModelProvider(this)[CollectionViewModel::class.java]

        // create collection adapter
        collectionAdapter = CollectionAdapter(
            requireContext(),
            this as CollectionAdapter.CollectionAdapterListener
        )
    }


    /* Overrides onCreate from Fragment*/
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        // find views and set them up
        val rootView: View = inflater.inflate(R.layout.fragment_player, container, false);
        layout = LayoutHolder(rootView)
        initializeViews()
        // hide action bar
        (activity as AppCompatActivity).supportActionBar?.hide()
        return rootView
    }


    /* Overrides onStart from Fragment */
    override fun onStart() {
        super.onStart()
        // initialize MediaController - connect to PlayerService
        initializeController()
    }


    /* Overrides onSaveInstanceState from Fragment */
    override fun onSaveInstanceState(outState: Bundle) {
        if (this::layout.isInitialized) {
            // save current state of station list
            listLayoutState = layout.layoutManager.onSaveInstanceState()
            outState.putParcelable(Keys.KEY_SAVE_INSTANCE_STATE_STATION_LIST, listLayoutState)
        }
        // always call the superclass so it can save the view hierarchy state
        super.onSaveInstanceState(outState)
    }


    /* Overrides onRestoreInstanceState from Activity */
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        // always call the superclass so it can restore the view hierarchy
        super.onActivityCreated(savedInstanceState)
        // restore state of station list
        listLayoutState = savedInstanceState?.getParcelable(Keys.KEY_SAVE_INSTANCE_STATE_STATION_LIST)
    }


    /* Overrides onResume from Fragment */
    override fun onResume() {
        super.onResume()
        // assign volume buttons to music volume
        activity?.volumeControlStream = AudioManager.STREAM_MUSIC
        // load player state
        playerState = PreferencesHelper.loadPlayerState()
        // load remote control setting
        remoteControlEnabled = PreferencesHelper.loadRemoteControlEnabled()
        // recreate player ui
        updatePlayerViews()
        updateStationListState()
        togglePeriodicSleepTimerUpdateRequest()
        // begin looking for changes in collection
        observeCollectionViewModel()
        // handle navigation arguments
        handleNavigationArguments()
        // start watching for changes in shared preferences
        PreferencesHelper.registerPreferenceChangeListener(this as SharedPreferences.OnSharedPreferenceChangeListener)
        // setup key listener for remote control
        setupKeyListenerForRemoteControl()
    }


    /* Overrides onPause from Fragment */
    override fun onPause() {
        super.onPause()
        // stop receiving playback progress updates
        handler.removeCallbacks(periodicSleepTimerUpdateRequestRunnable)
        // stop watching for changes in shared preferences
        PreferencesHelper.unregisterPreferenceChangeListener(this as SharedPreferences.OnSharedPreferenceChangeListener)

    }


    /* Overrides onStop from Fragment */
    override fun onStop() {
        super.onStop()
        // release MediaController - cut connection to PlayerService
        releaseController()
    }


    /* Register the ActivityResultLauncher */
    private val requestLoadImageLauncher =
        registerForActivityResult(StartActivityForResult(), this::requestLoadImageResult)


    /* Pass the activity result */
    private fun requestLoadImageResult(result: ActivityResult) {
        if (result.resultCode == RESULT_OK && result.data != null) {
            val imageUri: Uri? = result.data?.data
            if (imageUri != null) {
                collection = CollectionHelper.setStationImageWithStationUuid(requireContext(), collection, imageUri.toString(), tempStationUuid, imageManuallySet = true)
                tempStationUuid = String()
            }
        }
    }

    /* Register permission launcher */
    private val requestPermissionLauncher = registerForActivityResult(RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                // permission granted
                pickImage()
            } else {
                // permission denied
                Toast.makeText(requireContext(), R.string.toast_message_error_missing_storage_permission, Toast.LENGTH_LONG).show()
            }
        }


    /* Overrides onSharedPreferenceChanged from OnSharedPreferenceChangeListener */
    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == Keys.PREF_ACTIVE_DOWNLOADS) {
            layout.toggleDownloadProgressIndicator()
        }
        if (key == Keys.PREF_PLAYER_METADATA_HISTORY) {
            requestMetadataUpdate()
        }
        if (key == Keys.PREF_REMOTE_CONTROL_ENABLED) {
            remoteControlEnabled = PreferencesHelper.loadRemoteControlEnabled()
            setupKeyListenerForRemoteControl()
        }
        if (key == Keys.PREF_METADATA_CHARSET) {
            requestMetadataUpdate()
        }
    }


    /* Overrides onFindStationDialog from FindStationDialog */
    override fun onFindStationDialog(station: Station) {
        if (station.streamContent.isNotEmpty() && station.streamContent != Keys.MIME_TYPE_UNSUPPORTED) {
            // add station and save collection
            collection = CollectionHelper.addStation(requireContext(), collection, station)
        } else {
            // detect content type on background thread
            CoroutineScope(IO).launch {
                val contentType: NetworkHelper.ContentType = NetworkHelper.detectContentType(station.getStreamUri())
                // set content type
                station.streamContent = contentType.type
                // add station and save collection
                withContext(Main) {
                    CollectionHelper.addStation(requireContext(), collection, station) // todo check if should be moved to adapter (like removeStation)
                }
            }
        }
    }


    /* Overrides onAddStationDialog from AddDialog */
    override fun onAddStationDialog(station: Station) {
        if (station.streamContent.isNotEmpty() && station.streamContent != Keys.MIME_TYPE_UNSUPPORTED) {
            // add station and save collection
            CollectionHelper.addStation(requireContext(), collection, station) // todo check if should be moved to adapter (like removeStation)
        }
    }


    /* Overrides onPlayButtonTapped from CollectionAdapterListener */
    override fun onPlayButtonTapped(stationPosition: Int) {
        // CASE: the selected station is playing
        if (controller?.isPlaying == true && stationPosition == playerState.stationPosition) {
            // stop playback
            controller?.pause()
        }
        // CASE: the selected station is not playing (another station might be playing)
        else {
            playerState.stationPosition = stationPosition
            // start playback
            controller?.play(requireContext(), stationPosition)
        }
        updatePlayerViews()
    }


    /* Overrides onAddNewButtonTapped from CollectionAdapterListener */
    override fun onAddNewButtonTapped() {
        FindStationDialog(activity as Activity, this as FindStationDialog.FindStationDialogListener).show()
    }


    /* Overrides onChangeImageButtonTapped from CollectionAdapterListener */
    override fun onChangeImageButtonTapped(stationUuid: String) {
        tempStationUuid = stationUuid
        pickImage()
    }


    /* Overrides onStationFocused from CollectionAdapterListener - handles focus change from remote control */
    override fun onStationFocused(stationPosition: Int) {
        // update the player state position without playing
        playerState.stationPosition = stationPosition
        updatePlayerViews()
    }


    /* Overrides onYesNoDialog from YesNoDialogListener */
    override fun onYesNoDialog(type: Int, dialogResult: Boolean, payload: Int, payloadString: String) {
        super.onYesNoDialog(type, dialogResult, payload, payloadString)
        when (type) {
            // handle result of remove dialog
            Keys.DIALOG_REMOVE_STATION -> {
                when (dialogResult) {
                    // user tapped remove station
                    true -> {
                        collectionAdapter.removeStation(requireContext(), payload)
                        if (controller?.isPlaying == true && playerState.stationPosition == payload) {
                            // delete the currently playing station
                            playerState.isPlaying = false
                            // player pause, will cause playerListener method
                            controller?.pause()
                        }
                    }
                    // user tapped cancel
                    false -> collectionAdapter.notifyItemChanged(payload)
                }
            }
            // handle result from the restore collection dialog
            Keys.DIALOG_RESTORE_COLLECTION -> {
                when (dialogResult) {
                    // user tapped restore
                    true -> BackupHelper.restore(requireContext(), payloadString.toUri())
                    // user tapped cancel
                    false -> { /* do nothing */
                    }
                }
            }
        }
    }


    /* Initializes the MediaController - handles connection to PlayerService under the hood */
    private fun initializeController() {
        controllerFuture = MediaController.Builder(requireContext(), SessionToken(requireContext(), ComponentName(requireContext(), PlayerService::class.java))).buildAsync()
        controllerFuture.addListener({ setupController() }, MoreExecutors.directExecutor())
    }


    /* Releases MediaController */
    private fun releaseController() {
        MediaController.releaseFuture(controllerFuture)
    }


    /* Sets up the MediaController  */
    private fun setupController() {
        val controller: MediaController = this.controller ?: return
        controller.addListener(playerListener)
        requestMetadataUpdate()
        // handle start intent
        handleStartIntent()
        // wire up the playback controls
        setupPlaybackControls()
        // update play button state
        layout.togglePlayButton(controller.isPlaying)
        // auto play last station if enabled
        autoPlayLastStationIfEnabled()
    }


    /* Auto plays the last played station on app start if enabled */
    private fun autoPlayLastStationIfEnabled() {
        if (PreferencesHelper.loadAutoPlayLastStation()) {
            val lastPlayedPosition: Int = PreferencesHelper.loadLastPlayedStationPosition()
            if (lastPlayedPosition >= 0 && lastPlayedPosition < collection.stations.size) {
                // auto play the last station
                controller?.play(requireContext(), lastPlayedPosition)
            }
        }
    }


    /* Sets up views and connects tap listeners - first run */
    private fun initializeViews() {
        // set adapter data source
        layout.recyclerView.adapter = collectionAdapter

        // enable swipe to delete
        val swipeToDeleteHandler = object : UiHelper.SwipeToDeleteCallback(requireContext()) {
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // ask user
                val adapterPosition: Int = viewHolder.adapterPosition
                val dialogMessage: String = "${getString(R.string.dialog_yes_no_message_remove_station)}\n\n- ${collection.stations[adapterPosition].name}"
                YesNoDialog(this@BasePlayerFragment as YesNoDialog.YesNoDialogListener).show(context = requireContext(), type = Keys.DIALOG_REMOVE_STATION, messageString = dialogMessage, yesButton = R.string.dialog_yes_no_positive_button_remove_station, payload = adapterPosition)
            }
        }
        val swipeToDeleteItemTouchHelper = ItemTouchHelper(swipeToDeleteHandler)
        swipeToDeleteItemTouchHelper.attachToRecyclerView(layout.recyclerView)

        // enable swipe to mark starred
        val swipeToMarkStarredHandler =
            object : UiHelper.SwipeToMarkStarredCallback(requireContext()) {
                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    // mark card starred
                    val adapterPosition: Int = viewHolder.adapterPosition
                    collectionAdapter.toggleStarredStation(requireContext(), adapterPosition)
                }
            }
        val swipeToMarkStarredItemTouchHelper = ItemTouchHelper(swipeToMarkStarredHandler)
        swipeToMarkStarredItemTouchHelper.attachToRecyclerView(layout.recyclerView)

        // set up sleep timer start button
        layout.sheetSleepTimerStartButtonView.setOnClickListener {
            when (controller?.isPlaying) {
                true -> {
                    playerState.sleepTimerRunning = true
                    controller?.startSleepTimer()
                    togglePeriodicSleepTimerUpdateRequest()
                }

                else -> Toast.makeText(
                    requireContext(),
                    R.string.toast_message_sleep_timer_unable_to_start,
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        // set up sleep timer cancel button
        layout.sheetSleepTimerCancelButtonView.setOnClickListener {
            playerState.sleepTimerRunning = false
            controller?.cancelSleepTimer()
            togglePeriodicSleepTimerUpdateRequest()
        }

    }


    /* Sets up the general playback controls - Note: station specific controls and views are updated in updatePlayerViews() */
    private fun setupPlaybackControls() {
        // main play/pause button
        layout.playButtonView.setOnClickListener {
            onPlayButtonTapped(playerState.stationPosition)
        }
    }


    /* Sets up key listener for TV remote control */
    private fun setupKeyListenerForRemoteControl() {
        if (remoteControlEnabled) {
            layout.recyclerView.setOnKeyListener(this)
            layout.recyclerView.requestFocus()
        } else {
            layout.recyclerView.setOnKeyListener(null)
        }
    }


    /* Overrides onKey from View.OnKeyListener - handles TV remote control buttons */
    override fun onKey(view: View?, keyCode: Int, event: KeyEvent?): Boolean {
        if (!remoteControlEnabled) return false
        if (event == null) return false
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (collection.stations.isEmpty()) return false

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                // switch to previous station
                switchToPreviousStation()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                // switch to next station
                switchToNextStation()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                // toggle playback
                onPlayButtonTapped(playerState.stationPosition)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                // toggle playback
                onPlayButtonTapped(playerState.stationPosition)
                return true
            }
        }
        return false
    }


    /* Switches to the previous station in the list */
    private fun switchToPreviousStation() {
        if (collection.stations.isEmpty()) return

        val newPosition: Int
        if (playerState.stationPosition <= 0) {
            // wrap around to the last station
            newPosition = collection.stations.size - 1
        } else {
            newPosition = playerState.stationPosition - 1
        }

        // update player state and start playback
        playerState.stationPosition = newPosition
        controller?.play(requireContext(), newPosition)
        updatePlayerViews()

        // scroll to the new position and highlight it
        scrollToPositionWithOffset(newPosition)
    }


    /* Switches to the next station in the list */
    private fun switchToNextStation() {
        if (collection.stations.isEmpty()) return

        val newPosition: Int
        if (playerState.stationPosition >= collection.stations.size - 1) {
            // wrap around to the first station
            newPosition = 0
        } else {
            newPosition = playerState.stationPosition + 1
        }

        // update player state and start playback
        playerState.stationPosition = newPosition
        controller?.play(requireContext(), newPosition)
        updatePlayerViews()

        // scroll to the new position and highlight it
        scrollToPositionWithOffset(newPosition)
    }


    /* Scrolls to the given position and ensures it is visible */
    private fun scrollToPositionWithOffset(position: Int) {
        val layoutManager = layout.recyclerView.layoutManager
        if (layoutManager != null) {
            layoutManager.scrollToPosition(position)
            // post to ensure the view is laid out before highlighting
            layout.recyclerView.post {
                val viewHolder = layout.recyclerView.findViewHolderForAdapterPosition(position)
                if (viewHolder != null) {
                    viewHolder.itemView.requestFocus()
                }
            }
        }
    }


    /* Sets up the player */
    private fun updatePlayerViews() {
        // get station
        var station: Station = Station()
        if (playerState.stationPosition > -1 && playerState.stationPosition <= collection.stations.size -1) {
            // get station from player state
            station = collection.stations[playerState.stationPosition]
            layout.showPlayer(requireContext())
        } else if (collection.stations.isNotEmpty()) {
            // fallback: get first station
            station = collection.stations[0]
            playerState.stationPosition = 0
            layout.showPlayer(requireContext())
        } else {
            layout.hidePlayer(requireContext())
        }
        // update views
        layout.updatePlayerViews(requireContext(), station, playerState.isPlaying)
    }


    /* Sets up state of list station list */
    private fun updateStationListState() {
        if (listLayoutState != null) {
            layout.layoutManager.onRestoreInstanceState(listLayoutState)
        }
    }


    /* Requests an update of the sleep timer from the player service */
    private fun requestSleepTimerUpdate() {
        val resultFuture: ListenableFuture<SessionResult>? =
            controller?.requestSleepTimerRemaining()
        resultFuture?.addListener(kotlinx.coroutines.Runnable {
            val timeRemaining: Long = resultFuture.get().extras.getLong(Keys.EXTRA_SLEEP_TIMER_REMAINING)
            layout.updateSleepTimer(requireContext(), timeRemaining)
        }, MoreExecutors.directExecutor())
    }


    /* Requests an update of the metadata history from the player service */
    private fun requestMetadataUpdate() {
        val resultFuture: ListenableFuture<SessionResult>? = controller?.requestMetadataHistory()
        resultFuture?.addListener(kotlinx.coroutines.Runnable {
            val metadata: ArrayList<String>? = resultFuture.get().extras.getStringArrayList(Keys.EXTRA_METADATA_HISTORY)
            layout.updateMetadata(metadata?.toMutableList())
        }, MoreExecutors.directExecutor())
    }


    /* Check permissions and start image picker */
    private fun pickImage() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            // permission READ_EXTERNAL_STORAGE not granted - request permission
            requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            // permission READ_EXTERNAL_STORAGE granted - get system picker for images
            val pickImageIntent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            try {
                requestLoadImageLauncher.launch(pickImageIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Unable to select image. Probably no image picker available.")
                Toast.makeText(context, R.string.toast_message_no_image_picker, Toast.LENGTH_LONG).show()
            }
        }
    }


    /* Handles this activity's start intent */
    private fun handleStartIntent() {
        if ((activity as Activity).intent.action != null) {
            when ((activity as Activity).intent.action) {
                Keys.ACTION_SHOW_PLAYER -> handleShowPlayer()
                Intent.ACTION_VIEW -> handleViewIntent()
                Keys.ACTION_START -> handleStartPlayer()
            }
        }
        // clear intent action to prevent double calls
        (activity as Activity).intent.action = ""
    }


    /* Handles ACTION_SHOW_PLAYER request from notification */
    private fun handleShowPlayer() {
        Log.i(TAG, "Tap on notification registered.")
        // todo implement
    }


    /* Handles ACTION_VIEW request to add Station */
    private fun handleViewIntent() {
        val intentUri: Uri? = (activity as Activity).intent.data
        if (intentUri != null) {
            CoroutineScope(IO).launch {
                // get station list from intent source
                val stationList: MutableList<Station> = mutableListOf()
                val scheme: String = intentUri.scheme ?: String()
                // CASE: intent is a web link
                if (scheme.startsWith("http")) {
                    Log.i(TAG, "Transistor was started to handle a web link.")
                    stationList.addAll(CollectionHelper.createStationsFromUrl(intentUri.toString()))
                }
                // CASE: intent is a local file
                else if (scheme.startsWith("content")) {
                    Log.i(TAG, "Transistor was started to handle a local audio playlist.")
                    stationList.addAll(CollectionHelper.createStationListFromContentUri(requireContext(), intentUri))
                }
                withContext(Main) {
                    if (stationList.isNotEmpty()) {
                        AddStationDialog(activity as Activity, stationList, this@BasePlayerFragment as AddStationDialog.AddStationDialogListener).show()
                    } else {
                        // invalid address
                        Toast.makeText(context, R.string.toast_message_station_not_valid, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }


    /* Handles START_PLAYER_SERVICE request from App Shortcut */
    private fun handleStartPlayer() {
        val intent: Intent = (activity as Activity).intent
        if (intent.hasExtra(Keys.EXTRA_START_LAST_PLAYED_STATION)) {
            controller?.play(requireContext(), playerState.stationPosition)
        } else if (intent.hasExtra(Keys.EXTRA_STATION_UUID)) {
            val uuid: String = intent.getStringExtra(Keys.EXTRA_STATION_UUID) ?: String()
            controller?.play(requireContext(), CollectionHelper.getStationPosition(collection, uuid))
        } else if (intent.hasExtra(Keys.EXTRA_STREAM_URI)) {
            val streamUri: String = intent.getStringExtra(Keys.EXTRA_STREAM_URI) ?: String()
            controller?.playStreamDirectly(streamUri)
        }
    }


    /* Toggle periodic update request of Sleep Timer state from player service */
    private fun togglePeriodicSleepTimerUpdateRequest() {
        if (playerState.sleepTimerRunning && playerState.isPlaying) {
            handler.removeCallbacks(periodicSleepTimerUpdateRequestRunnable)
            handler.postDelayed(periodicSleepTimerUpdateRequestRunnable, 0)
        } else {
            handler.removeCallbacks(periodicSleepTimerUpdateRequestRunnable)
            layout.sleepTimerRunningViews.isGone = true
        }
    }


    /* Observe view model of collection of stations */
    private fun observeCollectionViewModel() {
        collectionViewModel.collectionLiveData.observe(
            this,
            Observer<Collection> { updatedCollection ->
                // val sizeChanged: Boolean = collection.stations.size != updatedCollection.stations.size
                // update collection
                collection = updatedCollection
                // update station player station position and update player views
                playerState.stationPosition = PreferencesHelper.loadLastPlayedStationPosition()
                updatePlayerViews()
                // todo only execute the below lines if the collection size has changed (note: the above sizeChanged check does not work)
                // toggle the onboarding view if necessary and export the collection
                layout.toggleOnboarding(requireContext(), collection.stations.size)
                if (collection.stations.size > 0) {
                    CoroutineScope(IO).launch {
                        FileHelper.backupCollectionAsM3u(requireContext(), collection)
                    }
                }
            })
    }


    /* Handles arguments handed over by navigation (from SettingsFragment) */
    private fun handleNavigationArguments() {
        // get arguments
        val updateCollection: Boolean = arguments?.getBoolean(Keys.ARG_UPDATE_COLLECTION, false) ?: false
        val updateStationImages: Boolean = arguments?.getBoolean(Keys.ARG_UPDATE_IMAGES, false) ?: false
        val restoreCollectionFileString: String? = arguments?.getString(Keys.ARG_RESTORE_COLLECTION)

        if (updateCollection) {
            arguments?.putBoolean(Keys.ARG_UPDATE_COLLECTION, false)
            val updateHelper: UpdateHelper = UpdateHelper(requireContext(), collectionAdapter, collection)
            updateHelper.updateCollection()
        }
        if (updateStationImages) {
            arguments?.putBoolean(Keys.ARG_UPDATE_IMAGES, false)
            DownloadHelper.updateStationImages(requireContext())
        }
        if (!restoreCollectionFileString.isNullOrEmpty()) {
            arguments?.putString(Keys.ARG_RESTORE_COLLECTION, null)
            when (collection.stations.isNotEmpty()) {
                true -> {
                    YesNoDialog(this as YesNoDialog.YesNoDialogListener).show(context = requireContext(), type = Keys.DIALOG_RESTORE_COLLECTION, messageString = getString(R.string.dialog_restore_collection_replace_existing), payloadString = restoreCollectionFileString)
                }

                false -> {
                    BackupHelper.restore(requireContext(), restoreCollectionFileString.toUri())
                }
            }
        }
    }


    /*
     * Runnable: Periodically requests sleep timer state
     */
    private val periodicSleepTimerUpdateRequestRunnable: Runnable = object : Runnable {
        override fun run() {
            // update sleep timer view
            requestSleepTimerUpdate()
            // use the handler to start runnable again after specified delay
            handler.postDelayed(this, 500)
        }
    }
    /*
     * End of declaration
     */


    /*
     * Player.Listener: Called when one or more player states changed.
     */
    private var playerListener: Player.Listener = object : Player.Listener {

//        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
//            super.onMediaItemTransition(mediaItem, reason)
//            // store new station
//            //playerState.stationUuid = mediaItem?.mediaId ?: String()
////            playerState.stationPosition = CollectionHelper.getStationPosition(collection, mediaItem?.mediaId ?: String())
//            //playerState.stationPosition = PreferencesHelper.loadLastPlayedStationPosition()
//            // update station specific views
//            updatePlayerViews()
//        }

//        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
//            super.onTimelineChanged(timeline, reason)
//            // update station specific views
//            updatePlayerViews()
//        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            super.onIsPlayingChanged(isPlaying)
            // store state of playback
            playerState.isPlaying = isPlaying
            // animate state transition of play button(s)
            layout.animatePlaybackButtonStateTransition(requireContext(), isPlaying)
            // toggle the sleep timer update subscription
            togglePeriodicSleepTimerUpdateRequest()

            if (isPlaying) {
                // playback is active
                layout.showPlayer(requireContext())
                layout.showBufferingIndicator(buffering = false)
            } else {
                // playback is not active
                layout.updateSleepTimer(requireContext())
            }

            // update the sleep timer running state
            val resultFuture: ListenableFuture<SessionResult>? = controller?.requestSleepTimerRunning()
            resultFuture?.addListener(kotlinx.coroutines.Runnable {
                playerState.sleepTimerRunning = resultFuture.get().extras.getBoolean(Keys.EXTRA_SLEEP_TIMER_RUNNING, false)
            }, MoreExecutors.directExecutor())

        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            super.onPlayWhenReadyChanged(playWhenReady, reason)

            if (playWhenReady && controller?.isPlaying == false) {
                layout.showBufferingIndicator(buffering = true)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            super.onPlayerError(error)
            layout.togglePlayButton(false)
            layout.showBufferingIndicator(false)
            // TODO: display Toast error message
        }
    }
    /*
     * End of declaration
     */
}