package com.geckour.egret.api.model

import com.google.gson.annotations.SerializedName
import java.io.Serializable
import java.net.URL
import java.util.*

class Account(
        val id: Long,

        var username: String,

        var acct: String,

        @SerializedName("display_name")
        var displayName: String,

        @SerializedName("locked")
        var isLocked: Boolean,

        @SerializedName("created_at")
        val createdAt: Date,

        @SerializedName("followers_count")
        var followersCount: Long,

        @SerializedName("following_count")
        var followingCount: Long,

        @SerializedName("statuses_count")
        var statusesCount: Long,

        val note: String,

        val url: URL,

        @SerializedName("avatar")
        val avatarUrl: String,

        @SerializedName("avatar_static")
        val avatarImg: String,

        @SerializedName("header")
        val headerUrl: String,

        @SerializedName("header_static")
        val headerImg: String
): Serializable