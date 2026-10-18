package com.shounak.localmeshai.models

import android.content.Context
import com.shounak.localmeshai.utils.DeviceUtils
import com.shounak.localmeshai.utils.InitCrashGuard

object ModelLoadRestrictionPolicy {

    /**
     * Recomputes load restrictions from device information and the existing crash record.
     */
    fun assessLoadRestriction(
        context: Context,
        model: ModelInfo
    ): LoadRestriction? {
        if (InitCrashGuard.isModelBlocked(context, model.id)) {
            return LoadRestriction(
                reason = InitCrashGuard.blockedModelMessage(),
                category = LoadRestrictionCategory.PreviousCrash
            )
        }
        val (allowed, reason) = DeviceUtils.checkModelSafetyProfile(
            context = context,
            modelInfo = model
        )
        if (!allowed && reason.isNotBlank()) {
            return LoadRestriction(
                reason = reason,
                category = LoadRestrictionCategory.DeviceCompatibility
            )
        }
        return null
    }

    /**
     * Offline overload for testing and parameter/RAM calculations.
     */
    fun assessLoadRestriction(
        isCrashBlocked: Boolean,
        model: ModelInfo,
        totalRamGB: Double
    ): LoadRestriction? {
        if (isCrashBlocked) {
            return LoadRestriction(
                reason = InitCrashGuard.blockedModelMessage(),
                category = LoadRestrictionCategory.PreviousCrash
            )
        }
        val (allowed, reason) = DeviceUtils.checkModelSafetyProfileForRam(
            totalRamGB = totalRamGB,
            modelInfo = model
        )
        if (!allowed && reason.isNotBlank()) {
            return LoadRestriction(
                reason = reason,
                category = LoadRestrictionCategory.DeviceCompatibility
            )
        }
        return null
    }

    /**
     * Projects a completed model's display status and load restriction while preserving
     * completed paths, bundles, permissions, and download counts.
     */
    fun projectCompletedModel(
        context: Context,
        model: ModelInfo
    ): ModelInfo {
        val inspectionRestriction = model.loadRestriction ?: model.bundle?.loadRestriction
        val restriction = when {
            InitCrashGuard.isModelBlocked(context, model.id) -> LoadRestriction(
                reason = InitCrashGuard.blockedModelMessage(),
                category = LoadRestrictionCategory.PreviousCrash
            )
            inspectionRestriction != null -> inspectionRestriction
            else -> assessLoadRestriction(context, model)
        }
        return model.copy(
            status = if (restriction != null) ModelStatus.Blocked else ModelStatus.Available,
            loadRestriction = restriction,
            errorMessage = restriction?.reason
        )
    }

    fun projectCompletedModel(
        isCrashBlocked: Boolean,
        model: ModelInfo,
        totalRamGB: Double
    ): ModelInfo {
        val inspectionRestriction = model.loadRestriction ?: model.bundle?.loadRestriction
        val restriction = when {
            isCrashBlocked -> LoadRestriction(
                reason = InitCrashGuard.blockedModelMessage(),
                category = LoadRestrictionCategory.PreviousCrash
            )
            inspectionRestriction != null -> inspectionRestriction
            else -> assessLoadRestriction(false, model, totalRamGB)
        }
        return model.copy(
            status = if (restriction != null) ModelStatus.Blocked else ModelStatus.Available,
            loadRestriction = restriction,
            errorMessage = restriction?.reason
        )
    }
}
