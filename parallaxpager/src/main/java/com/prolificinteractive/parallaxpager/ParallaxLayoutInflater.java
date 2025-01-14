package com.prolificinteractive.parallaxpager;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import androidx.core.os.BuildCompat;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.xmlpull.v1.XmlPullParser;

/**
 * Custom LayoutInflater that intercepts View creation and notifies ParallaxFactory of new views.
 * This class is a modified version from the Calligraphy project
 * @see uk.co.chrisjenx.calligraphy.CalligraphyLayoutInflater
 */
class ParallaxLayoutInflater extends LayoutInflater {

  private static final String[] sClassPrefixList = {
      "android.widget.",
      "android.webkit."
  };

  private boolean IS_AT_LEAST_Q = Build.VERSION.SDK_INT > Build.VERSION_CODES.P || BuildCompat.isAtLeastP();

  private final ParallaxFactory mParallaxFactory;
  // Reflection Hax
  private boolean mSetPrivateFactory = false;
  private Field mConstructorArgs = null;

  protected ParallaxLayoutInflater(LayoutInflater original, Context newContext,
      ParallaxFactory factory) {
    super(original, newContext);
    this.mParallaxFactory = factory;
  }

  @Override
  public LayoutInflater cloneInContext(Context newContext) {
    return new ParallaxLayoutInflater(this, newContext, mParallaxFactory);
  }

  // ===
  // Wrapping goodies
  // ===

  @Override
  public View inflate(XmlPullParser parser, ViewGroup root, boolean attachToRoot) {
    setPrivateFactoryInternal();
    return super.inflate(parser, root, attachToRoot);
  }

  /**
   * We don't want to unnecessary create/set our factories if there are none there. We try to be
   * as lazy as possible.
   */
  public void setUpLayoutFactories() {
    // If we are HC+ we get and set Factory2 otherwise we just wrap Factory1
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
      if (getFactory2() != null && !(getFactory2() instanceof WrapperFactory2)) {
        setFactory2(getFactory2());
        return;
      }
    }
    // We can do this as setFactory2 is used for both methods.
    if (getFactory() != null && !(getFactory() instanceof WrapperFactory)) {
      setFactory(getFactory());
    }
  }

  @Override
  public void setFactory(Factory factory) {
    // Only set our factory and wrap calls to the Factory trying to be set!
    if (!(factory instanceof WrapperFactory)) {
      super.setFactory(new WrapperFactory(factory, this, mParallaxFactory));
    } else {
      super.setFactory(factory);
    }
  }

  @Override
  @TargetApi(Build.VERSION_CODES.HONEYCOMB)
  public void setFactory2(Factory2 factory2) {
    // Only set our factory and wrap calls to the Factory2 trying to be set!
    if (!(factory2 instanceof WrapperFactory2)) {
      super.setFactory2(new WrapperFactory2(factory2, mParallaxFactory));
    } else {
      super.setFactory2(factory2);
    }
  }

  private void setPrivateFactoryInternal() {
    // Already tried to set the factory.
    if (mSetPrivateFactory) return;
    // Reflection (Or Old Device) skip.
    if (!(Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB)) { return; }
    // Skip if not attached to an activity.
    if (!(getContext() instanceof Factory2)) {
      mSetPrivateFactory = true;
      return;
    }

    final Method setPrivateFactoryMethod = ReflectionUtils
        .getMethod(LayoutInflater.class, "setPrivateFactory");

    if (setPrivateFactoryMethod != null) {
      ReflectionUtils.invokeMethod(this,
          setPrivateFactoryMethod,
          new PrivateWrapperFactory2((Factory2) getContext(), this, mParallaxFactory));
    }
    mSetPrivateFactory = true;
  }

  // ===
  // LayoutInflater ViewCreators
  // Works in order of inflation
  // ===

  /**
   * The LayoutInflater onCreateView is the fourth port of call for LayoutInflation.
   * BUT only for none CustomViews.
   */
  @Override
  @TargetApi(Build.VERSION_CODES.HONEYCOMB)
  protected View onCreateView(View parent, String name, AttributeSet attrs)
      throws ClassNotFoundException {
    return mParallaxFactory.onViewCreated(super.onCreateView(parent, name, attrs),
        getContext(), attrs);
  }

  /**
   * The LayoutInflater onCreateView is the fourth port of call for LayoutInflation.
   * BUT only for none CustomViews.
   * Basically if this method doesn't inflate the View nothing probably will.
   */
  @Override
  protected View onCreateView(String name, AttributeSet attrs) throws ClassNotFoundException {
    // This mimics the {@code PhoneLayoutInflater} in the way it tries to inflate the base
    // classes, if this fails its pretty certain the app will fail at this point.
    View view = null;
    for (String prefix : sClassPrefixList) {
      try {
        view = createView(name, prefix, attrs);
      } catch (ClassNotFoundException ignored) {
      }
    }
    // In this case we want to let the base class take a crack
    // at it.
    if (view == null) { view = super.onCreateView(name, attrs); }

    return mParallaxFactory.onViewCreated(view, view.getContext(), attrs);
  }

  /**
   * Nasty method to inflate custom layouts that haven't been handled else where. If this fails it
   * will fall back through to the PhoneLayoutInflater method of inflating custom views where
   * Calligraphy will NOT have a hook into.
   *
   * @param parent parent view
   * @param view view if it has been inflated by this point, if this is not null this method
   * just returns this value.
   * @param name name of the thing to inflate.
   * @param context Context to inflate by if parent is null
   * @param attrs Attr for this view which we can steal fontPath from too.
   * @return view or the View we inflate in here.
   */
  private View createCustomViewInternal(View parent, View view, String name, Context context,
      AttributeSet attrs) {
    // I by no means advise anyone to do this normally, but Google have locked down access to
    // the createView() method, so we never get a callback with attributes at the end of the
    // createViewFromTag chain (which would solve all this unnecessary rubbish).
    // We at the very least try to optimise this as much as possible.
    // We only call for customViews (As they are the ones that never go through onCreateView(...)).
    // We also maintain the Field reference and make it accessible which will make a pretty
    // significant difference to performance on Android 4.0+.

    // If CustomViewCreation is off skip this.
    if (view == null && name.indexOf('.') > -1) {
      if (IS_AT_LEAST_Q) {
        try {
          view = cloneInContext(context).createView(name, null, attrs);
        } catch (ClassNotFoundException ignored) {
        }
      } else {
        if (mConstructorArgs == null) {
          mConstructorArgs = ReflectionUtils.getField(LayoutInflater.class, "mConstructorArgs");
        }

        final Object[] mConstructorArgsArr =
                (Object[]) ReflectionUtils.getValue(mConstructorArgs, this);
        final Object lastContext = mConstructorArgsArr[0];
        mConstructorArgsArr[0] = parent != null ? parent.getContext() : context;
        ReflectionUtils.setValue(mConstructorArgs, this, mConstructorArgsArr);
        try {
          view = createView(name, null, attrs);
        } catch (ClassNotFoundException ignored) {
        } finally {
          mConstructorArgsArr[0] = lastContext;
          ReflectionUtils.setValue(mConstructorArgs, this, mConstructorArgsArr);
        }
      }
    }
    return view;
  }

  // ===
  // Wrapper Factories for Pre/Post HC
  // ===

  /**
   * Factory 1 is the first port of call for LayoutInflation
   */
  private static class WrapperFactory implements Factory {

    private final Factory mFactory;
    private final ParallaxLayoutInflater mInflater;
    private final ParallaxFactory mParallaxFactory;

    public WrapperFactory(Factory factory, ParallaxLayoutInflater inflater,
        ParallaxFactory parallaxFactory) {
      mFactory = factory;
      mInflater = inflater;
      mParallaxFactory = parallaxFactory;
    }

    @Override
    public View onCreateView(String name, Context context, AttributeSet attrs) {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
        return mParallaxFactory.onViewCreated(
            mInflater.createCustomViewInternal(
                null, mFactory.onCreateView(name, context, attrs), name, context, attrs
            ),
            context, attrs
        );
      }
      return mParallaxFactory.onViewCreated(
          mFactory.onCreateView(name, context, attrs),
          context, attrs
      );
    }
  }

  /**
   * Factory 2 is the second port of call for LayoutInflation
   */
  @TargetApi(Build.VERSION_CODES.HONEYCOMB)
  private static class WrapperFactory2 implements Factory2 {
    protected final Factory2 mFactory2;
    protected final ParallaxFactory mParallaxFactory;

    public WrapperFactory2(Factory2 factory2, ParallaxFactory parallaxFactory) {
      mFactory2 = factory2;
      mParallaxFactory = parallaxFactory;
    }

    @Override
    public View onCreateView(String name, Context context, AttributeSet attrs) {
      return mParallaxFactory.onViewCreated(
          mFactory2.onCreateView(name, context, attrs),
          context, attrs);
    }

    @Override
    public View onCreateView(View parent, String name, Context context, AttributeSet attrs) {
      return mParallaxFactory.onViewCreated(
          mFactory2.onCreateView(parent, name, context, attrs),
          context, attrs);
    }
  }

  /**
   * Private factory is step three for Activity Inflation, this is what is attached to the
   * Activity on HC+ devices.
   */
  @TargetApi(Build.VERSION_CODES.HONEYCOMB)
  private static class PrivateWrapperFactory2 extends WrapperFactory2 {

    private final ParallaxLayoutInflater mInflater;

    public PrivateWrapperFactory2(Factory2 factory2, ParallaxLayoutInflater inflater,
        ParallaxFactory parallaxFactory) {
      super(factory2, parallaxFactory);
      mInflater = inflater;
    }

    @Override
    public View onCreateView(View parent, String name, Context context, AttributeSet attrs) {
      return mParallaxFactory.onViewCreated(
          mInflater.createCustomViewInternal(parent,
              mFactory2.onCreateView(parent, name, context, attrs),
              name, context, attrs
          ),
          context, attrs
      );
    }
  }
}
