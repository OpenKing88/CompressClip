## How it works
在调用视频文件进行压缩时，视频库会检查用户是否希望设置最低比特率，以避免压缩低分辨率视频。如果不想每次处理视频时都对其进行压缩，以避免多轮压缩后视频质量变得很差，那么设置最小比特率就很方便。最小值是
* 比特率：2mbps

如果不想让视频库自动生成这些值，也可以传递自定义的 resizer 和 videoBitrate 值。

这些值已在大量视频上进行过测试，运行良好且速度很快。根据项目需要和预期，这些值可能会有所改变。

Usage
--------
要使用此库，必须添加以下权限，允许读写外部存储。请参考示例应用程序，了解如何通过正确设置启动压缩。

**API < 29**

```xml
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"/>
<uses-permission
    android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28"
    tools:ignore="ScopedStorage" />
```

**API >= 29**

```xml
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32"/>
```

**API >= 33**

```xml
 <uses-permission android:name="android.permission.READ_MEDIA_VIDEO"/>
```

```kotlin

 if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
     // request READ_MEDIA_VIDEO run-time permission
 } else {
     // request WRITE_EXTERNAL_STORAGE run-time permission
 }
```

并导入以下依赖项以使用 kotlin 协程
### Groovy
```groovy
implementation "org.jetbrains.kotlinx:kotlinx-coroutines-core:${Version.coroutines}"
implementation "org.jetbrains.kotlinx:kotlinx-coroutines-android:${Version.coroutines}"
```

然后只需调用 [VideoCompressor.start()]，并传递 **context**、**uris**、**isStreamable**、**configureWith** 和 **sharedStorageConfiguration 或 appSpecificStorageConfiguration**。

该方法有 5 个功能的回调；
1) OnStart - 压缩开始时调用
2) OnSuccess - 压缩完成且无错误/异常时调用
3) OnFailure - 出现异常或视频比特率和大小低于压缩所需的最小值时调用。
4) OnProgress - 当进度更新时调用
5) OnCancelled - 取消任务时调用

### Important Notes:

- 所有回调函数都会按照传递给视频库的 URL 的顺序返回一个正在压缩的视频索引。您可以使用该索引更新用户界面或检索有关原始 uri/文件的信息。
- 源视频必须以内容 uri 列表的形式提供。
- OnSuccess 会返回存储视频的路径。
- 如果您希望输出优化为流式的视频，请确保您传递的 [isStreamable] 标志为 true。

### Configuration values

- VideoQuality： VERY_HIGH (original-bitrate * 0.6) , HIGH (original-bitrate * 0.4), MEDIUM (original-bitrate * 0.3), LOW (original-bitrate * 0.2), OR VERY_LOW (original-bitrate * 0.1)

- isMinBitrateCheckEnabled：这表示如果比特率低于 2mbps 则不压缩

- videoBitrateInMbps：以 Mbps 为单位的任何自定义比特率值。

- disableAudio：true/false，用于生成无音频的视频。默认为假。

- resizer：调整视频尺寸的函数。默认为 `VideoResizer.auto`。

## 存储配置（StorageConfiguration）是一个接口，用于指示将文件保存在哪个库中。

#### 提供了一些更易于使用的行为，具体如下

### AppSpecificStorageConfiguration 配置值

- subFolderName: 在应用程序特定存储空间中创建的子文件夹名称。

### SharedStorageConfiguration 配置值

- saveAt：视频应保存的目录。必须是以下其中之一：[SaveLocation.pictures]、[SaveLocation.movies] 或 [SaveLocation.downloads]。
- subFolderName：在共享存储中创建的子文件夹名称。

### CacheStorageConfiguration
- 根据 Google 的定义，在缓存目录中创建文件没有配置值，如需了解更多信息，请访问 [此处](https://developer.android.com/training/data-storage/app-specific?hl=es-419)
### Fully custom configuration
- 如果这些没有符合你的需求，你可以自定义 StorageConfiguration，只需实现接口并将其传递给库即可
- 
```kotlin
 class AppCacheStorageConfiguration(
) : StorageConfiguration {
    val compressDir = PathUtils.getInternalAppCachePath() + File.separator + "compress"
    override fun createFileToSave(
        context: Context,
        videoFile: File,
        fileName: String,
        shouldSave: Boolean
    ): File {
        FileUtils.createOrExistsDir(compressDir)
        val outputName = "compressVideo.mp4"
        val out = compressDir + File.separator + outputName
        if (shouldSave) {
            FileUtils.getFileByPath(out).let {
                FileUtils.copy(videoFile, it)
                return it
            }
        }
        return File.createTempFile(videoFile.nameWithoutExtension, videoFile.extension)
    }

}

```

要取消压缩任务，只需调用 [VideoCompressor.cancel()] 即可。

### Kotlin

```kotlin
VideoCompressor.start(
   context = applicationContext, 
   uris = List<Uri>, 
   isStreamable = false, 
   storageConfiguration = SharedStorageConfiguration(
       saveAt = SaveLocation.movies, 
       subFolderName = "save-videos" 
   )
   configureWith = Configuration(
      videoNames = listOf<String>(), /*视频名称列表，大小应与传递的 uris 一致*/
      quality = VideoQuality.MEDIUM,
      isMinBitrateCheckEnabled = true,
      videoBitrateInMbps = 5, 
      disableAudio = false,
      startTime = 0,//开始时间 s
      endTime = 120,//结束时间 s
      resizer = VideoResizer.matchSize(360, 480)
   ),
   listener = object : CompressionListener {
       override fun onProgress(index: Int, percent: Float) {
          
          runOnUiThread {
          }
       }

       override fun onStart(index: Int) {
          
       }

       override fun onSuccess(index: Int, size: Long, path: String?) {
        
       }

       override fun onFailure(index: Int, failureMessage: String) {
         
       }

       override fun onCancelled(index: Int) {
         
       }

   }
)
```
