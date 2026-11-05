package app.mangalens.android;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.*;
public final class ApkProvider extends ContentProvider {
    @Override public boolean onCreate(){return true;}
    @Override public String getType(Uri uri){return "application/vnd.android.package-archive";}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{if(!"/update.apk".equals(uri.getPath())||!mode.equals("r"))throw new FileNotFoundException();return ParcelFileDescriptor.open(new File(getContext().getCacheDir(),"update.apk"),ParcelFileDescriptor.MODE_READ_ONLY);}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){
        if(!"/update.apk".equals(uri.getPath()))return null;android.database.MatrixCursor cursor=new android.database.MatrixCursor(new String[]{"_display_name","_size"});File f=new File(getContext().getCacheDir(),"update.apk");cursor.addRow(new Object[]{"MangaLens-Android.apk",f.length()});return cursor;
    }
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String where,String[] args){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String where,String[] args){throw new UnsupportedOperationException();}
}
