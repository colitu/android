package com.v2ray.ang.ui

import android.net.Uri
import android.os.Bundle
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.helper.FileChooserHelper
import com.v2ray.ang.helper.PermissionHelper

/**
 * HelperBaseActivity extends BaseActivity and provides additional helpers for
 * activities that need file chooser or permission requesting functionality.
 *
 * Activities that don't need these features should extend BaseActivity directly.
 * Activities that need file selection or permissions should extend this class.
 *
 * Additional Responsibilities:
 * - Provide file chooser helpers for selecting and creating files.
 * - Provide permission request helpers with callbacks.
 */
abstract class HelperBaseActivity : BaseActivity() {
    private lateinit var fileChooser: FileChooserHelper
    private lateinit var permissionRequester: PermissionHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fileChooser = FileChooserHelper(this)
        permissionRequester = PermissionHelper(this)
    }

    /**
     * Check if permission is granted and request it if not.
     * Convenience method that delegates to permissionRequester.
     *
     * @param permissionType The type of permission to check and request
     * @param onGranted Callback to execute when permission is granted
     */
    protected fun checkAndRequestPermission(
        permissionType: PermissionType,
        onGranted: () -> Unit
    ) {
        permissionRequester.request(permissionType, onGranted)
    }

    /**
     * Launch file chooser with ACTION_GET_CONTENT intent.
     * Convenience method that delegates to fileChooser helper.
     *
     * @param mimeType MIME type filter for files
     * @param onResult Callback invoked with the selected file URI (null if cancelled)
     */
    protected fun launchFileChooser(
        mimeType: String = "*/*",
        onResult: (Uri?) -> Unit
    ) {
        checkAndRequestPermission(PermissionType.READ_STORAGE) {
            fileChooser.launch(mimeType, onResult)
        }
    }

    /**
     * Launch document creator to create a new file at user-selected location.
     * Convenience method that delegates to fileChooser helper.
     * Note: No permission check needed as CreateDocument uses Storage Access Framework.
     *
     * @param fileName Default file name for the new document
     * @param onResult Callback invoked with the created file URI (null if cancelled)
     */
    protected fun launchCreateDocument(
        fileName: String,
        onResult: (Uri?) -> Unit
    ) {
        fileChooser.createDocument(fileName, onResult)
    }

}
