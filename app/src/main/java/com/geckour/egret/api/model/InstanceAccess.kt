package com.geckour.egret.api.model

import com.google.gson.annotations.SerializedName

data class InstanceAccess(
        @SerializedName("access_token")
        val accessToken: String
)