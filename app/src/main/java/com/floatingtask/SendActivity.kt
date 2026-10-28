package com.floatingtask

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * 拍照 → 预览 → 编辑文字 → 分享给 AI，一条龙。
 * 用户真的把内容分享出去（选择器回调 OK）才关悬浮窗；取消分享则留在编辑页。
 * 照片用系统相机拍（存私有目录），天然防相册选旧图。
 */
class SendActivity : Activity() {

    private lateinit var preview: ImageView
    private lateinit var editText: EditText
    private var photoFile: File? = null
    private val shareFile by lazy { File(cacheDir, "task_share.jpg") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        photoFile = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "task_${System.currentTimeMillis()}.jpg")
        launchCamera()
    }

    private fun launchCamera() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, FileProvider.getUriForFile(
                this@SendActivity, "$packageName.fileprovider", photoFile!!))
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, REQ_CAMERA)
    }

    private fun showEditor() {
        val ctx = this
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(32, 32, 32, 32)
        }
        preview = ImageView(ctx).apply { setImageBitmap(decodeScaled(photoFile!!, 1080)) }
        editText = EditText(ctx).apply {
            setText("任务【${TaskStore.taskText}】已完成拍照，请核验。")
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF1A1A24.toInt())
            setPadding(24, 24, 24, 24)
        }
        val send = Button(ctx).apply {
            text = "✅ 发送给 AI"
            setOnClickListener {
                decodeScaled(photoFile!!, 1080).compress(Bitmap.CompressFormat.JPEG, 88, FileOutputStream(shareFile))
                val uri = FileProvider.getUriForFile(ctx, "$packageName.fileprovider", shareFile)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_TEXT, editText.text.toString())
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivityForResult(Intent.createChooser(intent, "分享给 Orbis"), REQ_SHARE)
            }
        }
        val retake = Button(ctx).apply {
            text = "🔄 重拍"
            setOnClickListener { launchCamera() }
        }
        box.addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        box.addView(editText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 24 })
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(retake, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(send, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 24 })
        setContentView(box)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_CAMERA -> {
                if (resultCode == RESULT_OK && photoFile?.exists() == true) showEditor()
                else {
                    Toast.makeText(this, "未拍照，可返回重试", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            REQ_SHARE -> {
                if (resultCode == RESULT_OK || data != null) {
                    TaskWindowService.dismissWindow(this)
                    Toast.makeText(this, "已发送，悬浮任务完成 ✓", Toast.LENGTH_LONG).show()
                    finish()
                }
                // 用户取消分享 → 停留编辑页，可重试
            }
        }
    }

    private fun decodeScaled(f: File, max: Int): Bitmap {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, opts)
        var sample = 1
        while (opts.outWidth / sample > max || opts.outHeight / sample > max) sample *= 2
        val o = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(f.absolutePath, o)!!
    }

    companion object {
        private const val REQ_CAMERA = 41
        private const val REQ_SHARE = 42
    }
}
