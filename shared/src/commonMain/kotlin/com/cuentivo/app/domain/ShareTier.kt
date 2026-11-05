package com.cuentivo.app.domain

/**
 * The plan that the *shared* message and image are written for, which is not always the plan the
 * account holds.
 *
 * Exists for one debug switch: a tester with Pro never sees the free-plan footer, so there was no
 * way to look at it on a real phone. The switch forces [SubscriptionTier.FREE] here and only here —
 * sync, backups and the stored entitlement keep reading the real plan, because forcing FREE there
 * would stop a paying account from publishing to the cloud.
 *
 * [isDebugBuild] is a parameter, not a read of `BuildConfig`, so the release rule can be tested:
 * in a release build the switch does nothing, whatever it says.
 */
object ShareTier {
    fun resolve(actual: SubscriptionTier, isDebugBuild: Boolean, forceFree: Boolean): SubscriptionTier =
        if (isDebugBuild && forceFree) SubscriptionTier.FREE else actual
}
