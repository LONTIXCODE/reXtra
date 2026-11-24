package com.github.andreyasadchy.xtra.model.gql.chat

import com.github.andreyasadchy.xtra.model.gql.Error
import kotlinx.serialization.Serializable

@Serializable
class ChannelRewardsResponse(
    val data: Data? = null,
    val errors: List<Error>? = null,
) {
    @Serializable
    class Data(val community: Community? = null)

    @Serializable
    class Community(val channel: Channel? = null, val self: Viewer? = null)

    @Serializable
    class Viewer(val subscriptionBenefit: Subscription? = null)

    @Serializable
    class Subscription(val id: String)

    @Serializable
    class Channel(
        val id: String,
        val communityPointsSettings: Settings? = null,
        val self: Self? = null,
    )

    @Serializable
    class Self(val communityPoints: ChannelPointContextResponse.Points? = null)

    @Serializable
    class Settings(
        val isEnabled: Boolean,
        val customRewards: List<Reward?>? = null,
        val image: Image? = null,
        val defaultImage: Image? = null,
    ) {
        val imageUrl get() = image?.bestUrl ?: defaultImage?.bestUrl
    }

    @Serializable
    class Image(val url: String? = null, val url2x: String? = null, val url4x: String? = null) {
        val bestUrl get() = listOf(url4x, url2x, url).firstOrNull { it?.startsWith("https://") == true }
    }

    @Serializable
    class Reward(
        val id: String,
        val title: String,
        val prompt: String? = null,
        val cost: Int? = null,
        val pricingType: String? = null,
        val isEnabled: Boolean = false,
        val isPaused: Boolean = false,
        val isInStock: Boolean = false,
        val isSubOnly: Boolean = false,
        val isUserInputRequired: Boolean = false,
        val cooldownExpiresAt: String? = null,
        val image: Image? = null,
        val defaultImage: Image? = null,
    ) {
        val imageUrl get() = image?.bestUrl ?: defaultImage?.bestUrl
    }
}

@Serializable
class RedeemChannelRewardResponse(
    val data: Data? = null,
    val errors: List<Error>? = null,
) {
    @Serializable
    class Data(val redeemCommunityPointsCustomReward: Redemption? = null)

    @Serializable
    class Redemption(val error: RedemptionError? = null)

    @Serializable
    class RedemptionError(val code: String? = null)
}
