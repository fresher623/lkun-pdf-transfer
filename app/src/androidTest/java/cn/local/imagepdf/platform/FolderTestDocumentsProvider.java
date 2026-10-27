package cn.local.imagepdf.platform;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Framework/Java-only fixture; no dependency on Kotlin or classes from the target APK. */
public final class FolderTestDocumentsProvider extends DocumentsProvider {
    private static final Map<String, String> ROOTS = new LinkedHashMap<>();
    private static final String[] DOCUMENT_COLUMNS = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS
    };
    static {
        ROOTS.put("five-hundred", "一次选择 500 张");
        ROOTS.put("mixed", "混合文件夹");
        ROOTS.put("too-many", "过大文件夹");
        ROOTS.put("empty", "空文件夹");
    }

    public static boolean isFixtureRoot(String id) { return ROOTS.containsKey(id); }

    @Override public boolean onCreate() { return true; }

    @Override public Cursor queryRoots(String[] projection) {
        String[] columns = projection != null ? projection : new String[] {
            DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID, DocumentsContract.Root.COLUMN_FLAGS
        };
        // Do not advertise synthetic fixture folders in the user's normal system picker.
        return new MatrixCursor(columns);
    }

    @Override public Cursor queryDocument(String documentId, String[] projection) {
        MatrixCursor cursor = documentsCursor(projection);
        String rootName = ROOTS.get(documentId);
        if (rootName != null) {
            addFixture(cursor, documentId, rootName, DocumentsContract.Document.MIME_TYPE_DIR);
        } else if (documentId.startsWith("five-hundred:image:")) {
            addFixture(cursor, documentId, "图片_" + documentId.substring(documentId.lastIndexOf(':') + 1) + ".png", "image/png");
        } else {
            addFixture(cursor, documentId, documentId.substring(documentId.lastIndexOf(':') + 1), "image/jpeg");
        }
        return cursor;
    }

    @Override public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        MatrixCursor cursor = documentsCursor(projection);
        switch (parentDocumentId) {
            case "five-hundred":
                for (int index = 500; index >= 1; index--)
                    addFixture(cursor, "five-hundred:image:" + index, "图片_" + index + ".png", "image/png");
                break;
            case "mixed":
                addFixture(cursor, "mixed:ten", "10.jpg", "image/jpeg");
                addFixture(cursor, "mixed:directory", "nested.jpg", DocumentsContract.Document.MIME_TYPE_DIR);
                addFixture(cursor, "mixed:text", "notes.txt", "text/plain");
                addFixture(cursor, "mixed:two-first", "2.jpg", "image/jpeg");
                addFixture(cursor, "mixed:one", "1.jpg", "image/jpeg");
                addFixture(cursor, "mixed:two-second", "2.jpg", "application/octet-stream");
                addFixture(cursor, "mixed:animated", "动画.gif", "image/gif");
                addFixture(cursor, "mixed:extension", "photo.HEIC", "application/octet-stream");
                addFixture(cursor, "mixed:mime", "图像.bin", "image/png");
                break;
            case "too-many":
                for (int index = 1; index <= 10_001; index++)
                    addFixture(cursor, "too-many:" + index, index + ".jpg", "image/jpeg");
                break;
            case "empty":
                break;
            default:
                cursor.close();
                throw new FileNotFoundException("Nested listing is outside the fixture");
        }
        return cursor;
    }

    @Override public boolean isChildDocument(String parentDocumentId, String documentId) {
        return documentId.startsWith(parentDocumentId + ":");
    }

    @Override public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        if (!"r".equals(mode) || !documentId.startsWith("five-hundred:image:"))
            throw new FileNotFoundException("Unknown synthetic image");
        try {
            return ParcelFileDescriptor.open(fixturePng(), ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (IOException failure) {
            FileNotFoundException wrapped = new FileNotFoundException("Cannot create synthetic PNG");
            wrapped.initCause(failure);
            throw wrapped;
        }
    }

    private synchronized File fixturePng() throws IOException {
        Context context = getContext();
        if (context == null) throw new IOException("Provider is not initialized");
        File file = new File(context.getCacheDir(), "folder-provider-fixture.png");
        if (!file.isFile()) {
            Bitmap bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.BLUE);
            try (FileOutputStream output = new FileOutputStream(file)) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException("PNG encoding failed");
            } finally {
                bitmap.recycle();
            }
        }
        return file;
    }

    private static MatrixCursor documentsCursor(String[] projection) {
        return new MatrixCursor(projection != null ? projection : DOCUMENT_COLUMNS);
    }

    private static void addFixture(MatrixCursor cursor, String id, String name, String mime) {
        String[] columns = cursor.getColumnNames();
        Object[] values = new Object[columns.length];
        for (int index = 0; index < columns.length; index++) {
            switch (columns[index]) {
                case DocumentsContract.Document.COLUMN_DOCUMENT_ID: values[index] = id; break;
                case DocumentsContract.Document.COLUMN_DISPLAY_NAME: values[index] = name; break;
                case DocumentsContract.Document.COLUMN_MIME_TYPE: values[index] = mime; break;
                case DocumentsContract.Document.COLUMN_FLAGS: values[index] = 0; break;
                default: values[index] = null;
            }
        }
        cursor.addRow(values);
    }
}
