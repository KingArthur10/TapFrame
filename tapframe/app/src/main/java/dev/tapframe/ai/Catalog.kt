package dev.tapframe.ai

/**
 * The downloadable AI styles. The app has no internet permission: the user downloads each file
 * from its official source in the browser and imports it; files are identified by SHA-256, so they
 * can be picked in any order and anything else is rejected.
 */
object Catalog {
    enum class Kind { ANIMEGAN, DCTNET, SD_TURBO }

    class File(val name: String, val url: String, val sha256: String, val size: Long)

    /** One selectable style. [faces]: a DCT-Net head model to use on detected faces. */
    class Style(val id: String, val label: String, val pack: Pack, val file: String, val faces: String? = null, val prompt: String? = null)

    class Pack(
        val id: String, val title: String, val summary: String, val license: String, val kind: Kind,
        val files: List<File>, styleDefs: List<Triple<String, String, Map<String, String>>>,
    ) {
        val styles: List<Style> = styleDefs.map { (id, label, opts) ->
            Style(id, label, this, opts.getValue("file"), opts["faces"], opts["prompt"])
        }
        val size: Long get() = files.sumOf { it.size }
    }

    private const val ANIME = "https://github.com/TachibanaYoshino/AnimeGANv3/releases/download/v1.1.0/"
    private const val ANIME_PORTRAIT = "https://github.com/TachibanaYoshino/AnimeGANv3_Portrait_Inference/releases/download/1.0/"
    private fun modelScope(model: String, file: String) =
        "https://modelscope.cn/api/v1/models/damo/$model/repo?Revision=master&FilePath=$file"
    private const val SD = "https://huggingface.co/xay2001/sd-turbo-android/resolve/main/sd-turbo-v1/"
    private const val SD_TOKENIZER = "https://huggingface.co/stabilityai/sd-turbo/resolve/main/tokenizer/"

    private const val ANIME_LICENSE = "AnimeGANv3 license: free for non-commercial use (personal use is fine)."
    private const val DCT_LICENSE = "Apache 2.0 (DCT-Net, Alibaba DAMO Academy)."

    val packs: List<Pack> = listOf(
        Pack(
            "animegan", "AnimeGANv3 painting styles",
            "Repaints the whole picture like an animated film. Fast (under a second). Ghibli and Shinkai suit landscapes; " +
                "Ghibli portrait and Portrait sketch are made for people. Download any of the four.",
            ANIME_LICENSE, Kind.ANIMEGAN,
            listOf(
                File("AnimeGANv3_Hayao_36.onnx", ANIME + "AnimeGANv3_Hayao_36.onnx", "95ba7b219073fd5b12f569bc38056ffd3019cf4caf15b1feb9f73d1286c9f69d", 4232723),
                File("AnimeGANv3_Shinkai_37.onnx", ANIME + "AnimeGANv3_Shinkai_37.onnx", "8dc4d789c44db472447806583c56809e155280225b991bd79f9e2cb20793dcb9", 4233084),
                File("AnimeGANv3_large_Ghibli_c1_e299.onnx", ANIME_PORTRAIT + "AnimeGANv3_large_Ghibli_c1_e299.onnx", "ad950eabe2a556424b1cacf0f4b7847777ea44033482e673771da878054c1e37", 6971598),
                File("AnimeGANv3_PortraitSketch_25.onnx", ANIME_PORTRAIT + "AnimeGANv3_PortraitSketch_25.onnx", "86de643d216a387ae94d6881fc4197e3427e9dca31a030386a1526f8077c55d8", 4235352),
            ),
            listOf(
                Triple("anime-ghibli", "Ghibli", mapOf("file" to "AnimeGANv3_Hayao_36.onnx")),
                Triple("anime-shinkai", "Shinkai", mapOf("file" to "AnimeGANv3_Shinkai_37.onnx")),
                Triple("anime-ghibli-portrait", "Ghibli portrait", mapOf("file" to "AnimeGANv3_large_Ghibli_c1_e299.onnx")),
                Triple("anime-sketch", "Portrait sketch", mapOf("file" to "AnimeGANv3_PortraitSketch_25.onnx")),
            ),
        ),
        Pack(
            "dctnet", "DCT-Net portrait styles",
            "Turns people into cartoon characters while keeping them recognizable; faces get their own model. " +
                "Fast (about a second). Anime and Hand-drawn need two files each; the others one.",
            DCT_LICENSE, Kind.DCTNET,
            listOf(
                File("dct_anime_bg.pb", modelScope("cv_unet_person-image-cartoon_compound-models", "cartoon_anime_bg.pb"), "71efec51b4566cce3a3123822fb4e17794ad062c3e324b33f2a65df850c037ad", 5894895),
                File("dct_anime_h.pb", modelScope("cv_unet_person-image-cartoon_compound-models", "cartoon_anime_h.pb"), "338e7bf7ab4014ba9da864c2dda104f26d8af6c7f468038782d92ccc8e2dedda", 5894895),
                File("dct_3d.pb", modelScope("cv_unet_person-image-cartoon-3d_compound-models", "cartoon_h.pb"), "bca966dded317de32b6870b758fd01c6ab8f6ab76a285149fbf450a71624fe80", 5894895),
                File("dct_handdrawn_bg.pb", modelScope("cv_unet_person-image-cartoon-handdrawn_compound-models", "cartoon_bg.pb"), "fc1553a316698b1e7fa85a286bd2994e29ae8ece233004cb42192b5b17accc64", 5894895),
                File("dct_handdrawn_h.pb", modelScope("cv_unet_person-image-cartoon-handdrawn_compound-models", "cartoon_h.pb"), "b5c8c54cfc96420feacdde140c7c81c3b8e6612162d58bc70a6fd4d35b340ce2", 5894895),
                File("dct_sketch.pb", modelScope("cv_unet_person-image-cartoon-sketch_compound-models", "cartoon_h.pb"), "a6acab53c080318a6c8f0571e8db0f7922ad6bd84005ae30115a4dc6c89ed90f", 5894895),
                File("dct_artstyle.pb", modelScope("cv_unet_person-image-cartoon-artstyle_compound-models", "cartoon_h.pb"), "34ceb2100f1fcb665afa9af44d2bd49f280a4a031fc74681370303e61993bec9", 5894895),
            ),
            listOf(
                Triple("dct-anime", "Anime", mapOf("file" to "dct_anime_bg.pb", "faces" to "dct_anime_h.pb")),
                Triple("dct-3d", "3D cartoon", mapOf("file" to "dct_3d.pb", "faces" to "dct_3d.pb")),
                Triple("dct-handdrawn", "Hand-drawn", mapOf("file" to "dct_handdrawn_bg.pb", "faces" to "dct_handdrawn_h.pb")),
                Triple("dct-sketch", "Pencil sketch", mapOf("file" to "dct_sketch.pb", "faces" to "dct_sketch.pb")),
                Triple("dct-art", "Art style", mapOf("file" to "dct_artstyle.pb", "faces" to "dct_artstyle.pb")),
            ),
        ),
        Pack(
            "sdturbo", "SD-Turbo prompt styles",
            "Stable Diffusion redraws the picture in a described style (comic book, 3D film, storybook, ink drawing). " +
                "Large (2.4 GB, ten files) and slow (tens of seconds per picture); details and faces can change.",
            "Stability AI Community License (SD-Turbo; free for personal use); TAESD: MIT.", Kind.SD_TURBO,
            listOf(
                File("text_encoder.onnx", SD + "text_encoder.onnx", "26fab88d6a89c192d9a78acd9a2049965a4a10095a91703b02131cfaffa11d9a", 283405),
                File("text_encoder.data", SD + "text_encoder.data", "37dbb9bc1a0d6e1d77197ca275bb5a88e1baec2879570ccfb42800e4ea7cb347", 680775680),
                File("unet_512.onnx", SD + "unet_512.onnx", "d7e9916d39e0a574b444bd559fbfb99809b61c35e80fdb49042439d7440ef872", 795229),
                File("unet.data", SD + "unet.data", "e9d455aafd1941766b6e226c208eb9a98acaaddf1497248ee22a95e404d7d344", 1731755520),
                File("encoder_512.onnx", SD + "encoder_512.onnx", "70350d16b44cc545dfc2d2671f782be1c9c254352b022759b1fc723bbbfc6e6a", 26619),
                File("encoder.data", SD + "encoder.data", "623d1ddf076010d07270762910ccc0f40006c26542df311b67a9814047674e49", 2441088),
                File("decoder_512.onnx", SD + "decoder_512.onnx", "6418ddc628459a0ae6f934e9440ed6123ab3f0a3581dd1b009500ef09eeac1a6", 28480),
                File("decoder.data", SD + "decoder.data", "67e1bcd20f0967d45e95d7abfa12fb54f74ea90bf40561f55a01592bd9b5ce43", 2441088),
                File("vocab.json", SD_TOKENIZER + "vocab.json", "e089ad92ba36837a0d31433e555c8f45fe601ab5c221d4f607ded32d9f7a4349", 1059962),
                File("merges.txt", SD_TOKENIZER + "merges.txt", "9fd691f7c8039210e0fced15865466c65820d09b63988b0174bfe25de299051a", 524619),
            ),
            listOf(
                Triple("sd-comic", "Comic book", mapOf("file" to "unet_512.onnx", "prompt" to "comic book illustration, bold black ink outlines, flat colors, cel shading")),
                Triple("sd-3d", "3D film", mapOf("file" to "unet_512.onnx", "prompt" to "3d animated movie still, cute stylized characters, soft lighting")),
                Triple("sd-storybook", "Storybook", mapOf("file" to "unet_512.onnx", "prompt" to "children's storybook illustration, gouache painting, simple shapes")),
                Triple("sd-ink", "Ink drawing", mapOf("file" to "unet_512.onnx", "prompt" to "black and white ink drawing, clean bold lines, high contrast")),
            ),
        ),
    )

    val styles: List<Style> = packs.flatMap { it.styles }

    fun style(id: String): Style? = styles.firstOrNull { it.id == id }

    /** Catalog file by SHA-256 (lowercase hex). */
    fun fileByHash(sha256: String): Pair<Pack, File>? =
        packs.firstNotNullOfOrNull { p -> p.files.firstOrNull { it.sha256 == sha256 }?.let { p to it } }
}
