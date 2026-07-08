package app.filmengine.camera

import android.app.Application
import android.content.Context
import app.filmengine.camera.core.CameraController
import app.filmengine.camera.core.PreviewPipeline
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@HiltAndroidApp
class FilmEngineApp : Application()

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun cameraController(@ApplicationContext context: Context): CameraController =
        CameraController(context)

    @Provides
    @Singleton
    fun previewPipeline(): PreviewPipeline = PreviewPipeline()
}
