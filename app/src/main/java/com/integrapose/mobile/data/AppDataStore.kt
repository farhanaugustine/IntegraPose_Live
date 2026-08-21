package com.integrapose.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.integrapose.mobile.inference.AnnotationColorPreset
import com.integrapose.mobile.inference.AnnotationStyle
import com.integrapose.mobile.inference.RoiLabelSize
import com.integrapose.mobile.live.LiveOverlayRefreshRate
import com.integrapose.mobile.live.LivePreviewQuality
import com.integrapose.mobile.live.LivePreviewRenderer
import com.integrapose.mobile.live.LiveRawVideoQuality
import com.integrapose.mobile.tracking.IoUTrackerConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore by preferencesDataStore(name = "integra_pose_prefs")

class AppDataStore(private val context: Context) {
    private val agreementAcceptedKey = booleanPreferencesKey("agreement_accepted")
    private val selectedModelIdKey = stringPreferencesKey("selected_model_id")
    private val boundingBoxColorKey = stringPreferencesKey("bounding_box_color")
    private val keypointColorKey = stringPreferencesKey("keypoint_color")
    private val roiLabelSizeKey = stringPreferencesKey("roi_label_size")
    private val showClassIndexKey = booleanPreferencesKey("show_class_index")
    private val trackerMinimumConfidenceKey =
        floatPreferencesKey("tracker_minimum_confidence")
    private val trackerNewTrackConfidenceKey =
        floatPreferencesKey("tracker_new_track_confidence")
    private val trackerMatchIoUKey = floatPreferencesKey("tracker_match_iou")
    private val trackerMaxMissingFramesKey =
        intPreferencesKey("tracker_max_missing_frames")
    private val liveRawVideoQualityKey = stringPreferencesKey("live_raw_video_quality")
    private val livePreviewQualityKey = stringPreferencesKey("live_preview_quality")
    private val livePreviewRendererKey = stringPreferencesKey("live_preview_renderer")
    private val liveOverlayRefreshRateKey = stringPreferencesKey("live_overlay_refresh_rate")

    val agreementAcceptedFlow: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences: Preferences -> preferences[agreementAcceptedKey] ?: false }

    val selectedModelIdFlow: Flow<String?> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences -> preferences[selectedModelIdKey] }

    val annotationStyleFlow: Flow<AnnotationStyle> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            AnnotationStyle(
                boundingBoxColor = AnnotationColorPreset.fromStoredName(
                    preferences[boundingBoxColorKey],
                    AnnotationStyle.Default.boundingBoxColor
                ),
                keypointColor = AnnotationColorPreset.fromStoredName(
                    preferences[keypointColorKey],
                    AnnotationStyle.Default.keypointColor
                ),
                roiLabelSize = RoiLabelSize.fromStoredName(
                    preferences[roiLabelSizeKey],
                    AnnotationStyle.Default.roiLabelSize
                ),
                showClassIndex = preferences[showClassIndexKey]
                    ?: AnnotationStyle.Default.showClassIndex
            )
        }

    val trackerConfigFlow: Flow<IoUTrackerConfig> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val defaults = IoUTrackerConfig()
            IoUTrackerConfig(
                minimumConfidence = preferences[trackerMinimumConfidenceKey]
                    ?: defaults.minimumConfidence,
                newTrackConfidence = preferences[trackerNewTrackConfidenceKey]
                    ?: defaults.newTrackConfidence,
                matchIoU = preferences[trackerMatchIoUKey]
                    ?: defaults.matchIoU,
                maxMissingFrames = preferences[trackerMaxMissingFramesKey]
                    ?: defaults.maxMissingFrames
            ).sanitized()
        }

    val liveRawVideoQualityFlow: Flow<LiveRawVideoQuality> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            LiveRawVideoQuality.fromStoredName(preferences[liveRawVideoQualityKey])
        }

    val livePreviewQualityFlow: Flow<LivePreviewQuality> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences ->
            LivePreviewQuality.fromStoredName(preferences[livePreviewQualityKey])
        }

    val livePreviewRendererFlow: Flow<LivePreviewRenderer> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences ->
            LivePreviewRenderer.fromStoredName(preferences[livePreviewRendererKey])
        }

    val liveOverlayRefreshRateFlow: Flow<LiveOverlayRefreshRate> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences ->
            LiveOverlayRefreshRate.fromStoredName(preferences[liveOverlayRefreshRateKey])
        }

    suspend fun isAgreementAccepted(): Boolean = agreementAcceptedFlow.first()

    suspend fun setAgreementAccepted(accepted: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[agreementAcceptedKey] = accepted
        }
    }

    suspend fun setSelectedModelId(modelId: String?) {
        context.dataStore.edit { prefs ->
            if (modelId.isNullOrBlank()) {
                prefs.remove(selectedModelIdKey)
            } else {
                prefs[selectedModelIdKey] = modelId
            }
        }
    }

    suspend fun setAnnotationStyle(style: AnnotationStyle) {
        context.dataStore.edit { prefs ->
            prefs[boundingBoxColorKey] = style.boundingBoxColor.name
            prefs[keypointColorKey] = style.keypointColor.name
            prefs[roiLabelSizeKey] = style.roiLabelSize.name
            prefs[showClassIndexKey] = style.showClassIndex
        }
    }

    suspend fun setTrackerConfig(config: IoUTrackerConfig) {
        val sanitized = config.sanitized()
        context.dataStore.edit { prefs ->
            prefs[trackerMinimumConfidenceKey] = sanitized.minimumConfidence
            prefs[trackerNewTrackConfidenceKey] = sanitized.newTrackConfidence
            prefs[trackerMatchIoUKey] = sanitized.matchIoU
            prefs[trackerMaxMissingFramesKey] = sanitized.maxMissingFrames
        }
    }

    suspend fun setLiveRawVideoQuality(quality: LiveRawVideoQuality) {
        context.dataStore.edit { prefs ->
            prefs[liveRawVideoQualityKey] = quality.storageName
        }
    }

    suspend fun setLivePreviewQuality(quality: LivePreviewQuality) {
        context.dataStore.edit { prefs ->
            prefs[livePreviewQualityKey] = quality.storageName
        }
    }

    suspend fun setLivePreviewRenderer(renderer: LivePreviewRenderer) {
        context.dataStore.edit { prefs ->
            prefs[livePreviewRendererKey] = renderer.storageName
        }
    }

    suspend fun setLiveOverlayRefreshRate(rate: LiveOverlayRefreshRate) {
        context.dataStore.edit { prefs ->
            prefs[liveOverlayRefreshRateKey] = rate.storageName
        }
    }
}
