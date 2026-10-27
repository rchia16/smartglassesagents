plugins {
    alias(libs.plugins.android.asset.pack)
}

assetPack {
    packName.set("fastvlm_model")
    dynamicDelivery {
        deliveryType.set("fast-follow")
    }
}
