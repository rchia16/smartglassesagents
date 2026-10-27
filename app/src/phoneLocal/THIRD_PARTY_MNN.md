# MNN runtime

The `arm64-v8a` libraries in this source set are from the official MNN Chat
0.5.0 Android release, which introduced FastVLM support:

- https://github.com/alibaba/MNN
- https://meta.alicdn.com/data/mnn/mnn_chat_0_5_0.apk

MNN is licensed under Apache License 2.0. Only `libMNN.so` and
`libmnnllmapp.so` are included. They are isolated to the `phoneLocal` flavor.

The newer 0.8.2.2 and 0.8.3 JNI libraries are not used because their Android
`PromptProcessor::LoadImageFromPath` implementation is a stub that always
fails image loading. That makes a VLM fall back to text-only inference. Keep
these two 0.5.0 libraries together because they form a matched runtime pair.

SHA-256 checksums:

- `libMNN.so`: `0E054301BBEDA4D0B6411E68994FB79191CC6C576F6DDD81F6917992955D12E7`
- `libmnnllmapp.so`: `E8395BF7B6C0274D4A05AD7001F6C1786638B410D94FBEB1B1FFD3B64F5D3B7E`
