package org.aquosr7.colorednotifications;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.ColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ScaleDrawable;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.Gravity;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

public final class Entry extends XposedModule {
 private static final String TAG="AQUOSR7ColoredNotifications";
 private static final String SYSTEM_UI="com.android.systemui";
 private static final String ICON_VIEW="com.android.systemui.statusbar.StatusBarIconView";
 private static final float APP_ICON_SCALE=0.78f;
 @Override public void onModuleLoaded(ModuleLoadedParam p){Log.i(TAG,"MODULE_LOADED process="+p.getProcessName());}
 @Override public void onPackageLoaded(PackageLoadedParam p){Log.i(TAG,"PACKAGE_LOADED package="+p.getPackageName());}
 @Override public void onPackageReady(PackageReadyParam p){
  Log.i(TAG,"PACKAGE_READY package="+p.getPackageName());
  if(!SYSTEM_UI.equals(p.getPackageName()))return;
  try{
   Class<?> c=p.getClassLoader().loadClass(ICON_VIEW);
   installApplicationIconHook(c);
   installColorHooks(c);
   Log.i(TAG,"Initialization finished");
  }catch(Throwable t){Log.e(TAG,"SystemUI hook initialization failed",t);}
 }
 // Exact Android 14 AQUOS R7 DEX signature: getIcon(StatusBarIcon): Drawable.
 private void installApplicationIconHook(Class<?> c){
  try{
   Class<?> icon=c.getClassLoader().loadClass("com.android.internal.statusbar.StatusBarIcon");
   Method m=findMethod(c,"getIcon",icon);
   if(m==null)throw new NoSuchMethodException("getIcon(StatusBarIcon)");
   m.setAccessible(true);
   hook(m).setId("aquosr7-original-app-icon").intercept(chain->{
    Drawable stock=(Drawable)chain.proceed();
    Object view=chain.getThisObject();
    StatusBarNotification n=getNotification(view);
    if(stock==null||n==null||n.getPackageName()==null||SYSTEM_UI.equals(n.getPackageName()))return stock;
    try{
     Drawable app=((Context)view).getPackageManager().getApplicationIcon(n.getPackageName());
     ScaleDrawable scaled=new ScaleDrawable(app,Gravity.CENTER,APP_ICON_SCALE,APP_ICON_SCALE);
     scaled.setLevel(10000);
     Log.d(TAG,"APP_ICON_REPLACED "+n.getPackageName());
     return scaled;
    }catch(PackageManager.NameNotFoundException e){Log.w(TAG,"App icon not found: "+n.getPackageName(),e);return stock;}
    catch(Throwable t){Log.e(TAG,"App icon replacement failed: "+n.getPackageName(),t);return stock;}
   });
   Log.i(TAG,"Installed getIcon(StatusBarIcon)");
  }catch(Throwable t){Log.e(TAG,"Cannot install application icon hook",t);}
 }
 private void installColorHooks(Class<?> c){
  try{
   Field f=findField(c,"NO_COLOR");if(f==null)throw new NoSuchFieldException("NO_COLOR");
   f.setAccessible(true);final int noColor=f.getInt(null);
   Method m=findMethod(c,"setStaticDrawableColor",int.class);
   if(m==null)throw new NoSuchMethodException("setStaticDrawableColor(int)");
   m.setAccessible(true);
   hook(m).setId("aquosr7-no-static-app-tint").intercept(ch->isApp(ch.getThisObject())?ch.proceed(new Object[]{noColor}):ch.proceed());
   Log.i(TAG,"Installed setStaticDrawableColor");
  }catch(Throwable t){Log.e(TAG,"setStaticDrawableColor hook failed",t);}
  try{
   Method m=findMethod(c,"setIconColor",int.class,boolean.class);
   if(m==null)throw new NoSuchMethodException("setIconColor(int,boolean)");
   Field f=findField(c,"NO_COLOR");if(f==null)throw new NoSuchFieldException("NO_COLOR");
   f.setAccessible(true);final int noColor=f.getInt(null);m.setAccessible(true);
   hook(m).setId("aquosr7-no-app-icon-color").intercept(ch->isApp(ch.getThisObject())?ch.proceed(new Object[]{noColor,false}):ch.proceed());
   Log.i(TAG,"Installed setIconColor");
  }catch(Throwable t){Log.e(TAG,"setIconColor hook failed",t);}
  for(String name:new String[]{"updateIconColor","updateContrastedStaticColor"}){
   try{
    Method m=findMethod(c,name);if(m==null)throw new NoSuchMethodException(name);
    m.setAccessible(true);
    hook(m).setId("aquosr7-clear-"+name).intercept(ch->{Object v=ch.getThisObject();Object r=ch.proceed();if(isApp(v))clearTint(v);return r;});
    Log.i(TAG,"Installed "+name);
   }catch(Throwable t){Log.e(TAG,"Tint hook failed: "+name,t);}
  }
 }
 private static boolean isApp(Object v){StatusBarNotification n=getNotification(v);return n!=null&&n.getPackageName()!=null&&!SYSTEM_UI.equals(n.getPackageName());}
 private static StatusBarNotification getNotification(Object v){
  if(v==null)return null;
  try{Field f=findField(v.getClass(),"mNotification");if(f==null)return null;f.setAccessible(true);Object x=f.get(v);return x instanceof StatusBarNotification?(StatusBarNotification)x:null;}
  catch(Throwable t){Log.w(TAG,"Cannot read mNotification",t);return null;}
 }
 private static void clearTint(Object v){
  invoke(v,"setColorFilter",ColorFilter.class,null);
  invoke(v,"setImageTintList",ColorStateList.class,null);
 }
 private static void invoke(Object v,String n,Class<?> p,Object x){
  try{Method m=findMethod(v.getClass(),n,p);if(m!=null){m.setAccessible(true);m.invoke(v,x);}}
  catch(Throwable t){Log.w(TAG,"Cannot invoke "+n,t);}
 }
 private static Method findMethod(Class<?> c,String n,Class<?>...p){
  for(Class<?> x=c;x!=null;x=x.getSuperclass())try{return x.getDeclaredMethod(n,p);}catch(NoSuchMethodException ignored){}
  return null;
 }
 private static Field findField(Class<?> c,String n){
  for(Class<?> x=c;x!=null;x=x.getSuperclass())try{return x.getDeclaredField(n);}catch(NoSuchFieldException ignored){}
  return null;
 }
}
