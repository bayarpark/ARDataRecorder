package fr.smarquis.ar_toolbox

import android.content.Context
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.media.CamcorderProfile
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.FileProvider
import com.google.ar.core.Pose
import com.google.ar.sceneform.SceneView
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream


class ARVideoRecorder(
    private val context: Context,
    private val sceneView: SceneView,
    private val onRecordingListener: (isRecording: Boolean) -> Unit,
) {
    companion object {
        private const val TAG = "VideoRecorder"
    }

    var isRecording: Boolean = false
        private set

    private val mediaRecorder: MediaRecorder = MediaRecorder()

    private var videoFile: File? = null
    private var arLogFile: File? = null
    private var archiveFile: File? = null

    fun start(profile: CamcorderProfile) {
        if (isRecording) {
            return
        }
        try {
            val name = filename()
            arLogFile = definedCacheFile(context, name,".csv")
            archiveFile = definedCacheFile(context, name,".zip")

            videoFile = definedCacheFile(context, name,".mp4").apply { createNewFile() }
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setOutputFile(videoFile!!.absolutePath)
            mediaRecorder.setVideoEncodingBitRate(profile.videoBitRate)
            mediaRecorder.setVideoFrameRate(profile.videoFrameRate)
            mediaRecorder.setVideoSize(profile.width(), profile.height())
            mediaRecorder.setVideoEncoder(profile.videoCodec)
            mediaRecorder.prepare()
            mediaRecorder.start()
        } catch (e: IOException) {
            Log.e(TAG, "Exception starting recorder", e)
            return
        }
        sceneView.startMirroringToSurface(mediaRecorder.surface, 0, 0, profile.width(), profile.height())
        isRecording = true
        onRecordingListener(true)
    }

    fun stop() {
        if (!isRecording) {
            return
        }
        sceneView.stopMirroringToSurface(mediaRecorder.surface)
        mediaRecorder.stop()
        mediaRecorder.reset()
        isRecording = false
        onRecordingListener(false)
    }

    fun export() {
//        val cachedVideoURI = FileProvider.getUriForFile(context, context.packageName, videoFile ?: return).toString()
//        val cachedImuURI = FileProvider.getUriForFile(context, context.packageName, arLogFile ?: return).toString()
        
        zip(arrayOf(videoFile.toString(), arLogFile.toString()), archiveFile.toString())
        
        val uri = FileProvider.getUriForFile(context, context.packageName, archiveFile ?: return)
        context.startActivity(viewOrShare(uri, "application/zip"))
    }

    fun writePosition(pose: Pose?) {
        if (!isRecording) {
            return
        }

        try {
            if (!arLogFile?.exists()!!) {
                if (arLogFile!!.createNewFile()) {
                    Log.i("SceneActivity", "Output videoFile created at " + arLogFile!!.absolutePath)
                    val outputCSVFile = FileWriter(arLogFile, true)
                    val columns = "x,y,z,qx,qy,qz,qw\r\n"
                    outputCSVFile.write(columns)
                    outputCSVFile.flush()
                    outputCSVFile.close()
                }
            }
            if (arLogFile!!.exists()) {
                Log.i("SceneActivity", "Add data to output videoFile " + arLogFile!!.absolutePath)
                val outputCSVFile = FileWriter(arLogFile, true)
                val currentPoseEst = listOf(
                    // translation
                    (pose?.tx() ?: 0F),
                    (pose?.ty() ?: 0F),
                    (pose?.tz() ?: 0F),

                    // rotation (quaternion)
                    (pose?.qx() ?: 0F),
                    (pose?.qy() ?: 0F),
                    (pose?.qz() ?: 0F),
                    (pose?.qw() ?: 0F),
                )

                val data = currentPoseEst.joinToString(separator = ",")
                    .plus("\r\n")

                Log.i("SceneActivity", data)

                outputCSVFile.write(data)
                outputCSVFile.flush()
                outputCSVFile.close()
            } else {
                Log.e("SceneActivity", "Output videoFile not exists")
            }
        } catch (e: Exception) {
            Log.e("SceneActivity", "Exception writing to videoFile", e)
            return
        }
    }

    @Throws(IOException::class)
    fun zip(files: Array<String>, zipFile: String?) {
        val zipBufferSize = 1024*1024
        
        var origin: BufferedInputStream? = null
        val out = ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile)))
        try {
            val data = ByteArray(zipBufferSize)
            for (i in files.indices) {
                val fi = FileInputStream(files[i])
                origin = BufferedInputStream(fi, zipBufferSize)
                try {
                    val entry = ZipEntry(files[i].substring(files[i].lastIndexOf("/") + 1))
                    out.putNextEntry(entry)
                    var count: Int
                    while (origin.read(data, 0, zipBufferSize).also { count = it } != -1) {
                        out.write(data, 0, count)
                    }
                } finally {
                    origin.close()
                }
            }
        } finally {
            out.close()
        }
    }

    private fun orientation() = context.resources.configuration.orientation

    private fun isLandscape() = orientation() == ORIENTATION_LANDSCAPE

    private fun CamcorderProfile.width(): Int = if (isLandscape()) videoFrameWidth else videoFrameHeight

    private fun CamcorderProfile.height(): Int = if (isLandscape()) videoFrameHeight else videoFrameWidth
}
