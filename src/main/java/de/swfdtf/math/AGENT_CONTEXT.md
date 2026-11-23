# Package `de.swfdtf.math` — AGENT_CONTEXT

## Zweck des Pakets
Mathematische Kernprimitiven und Transformationsmodelle für KAGAYAKI: 2D-Vektoren, achsenparallele Rechtecke, Premultiplied-Alpha-konforme 4×4-Farbmatrizen, Easing-/Kubische-Bézier-Kurven, Ren'Py-kompatible 2D-Sprite-Transformationen (`Transform2D`) sowie gedämpfte Screen-Shake-Oszillatoren.

## Abhängigkeiten zu anderen `de.swfdtf.*`-Paketen
- Keine internen Paketabhängigkeiten (nutzt ausschließlich `java.util.*`).

## Enthaltene Typen

| Typ | Art | Beschreibung |
| :--- | :--- | :--- |
| `Vec2f` | `record` | Unveränderlicher 2D-Gleitkomma-Vektor `(x, y)` inklusive Arithmetik, Interpolation und Distanzfunktionen. |
| `Rect2f` | `record` | Unveränderliches achsenparalleles 2D-Rechteck mit Ursprung `(x, y)` sowie Hit-Testing-, Schnitt- und Inset-Operationen. |
| `ColorMatrix4f` | `final class` | Unveränderliche 4×4-Farbtransformationsmatrix in Column-Major-Reihenfolge für OpenGL (`glUniformMatrix4fv`) und Premultiplied-Alpha-RGBA. |
| `Easing` | `final class` | Statische Standard-Easing-Kurven, Hermite-Smoothstep und CSS-konforme kubische Bézier-Evaluation für ATL-Animationen und UI-Übergänge. |
| `Easing.EaseType` | `enum` | Vordefinierte Easing-Presets (`LINEAR`, `EASE`, `EASE_IN`, `EASE_OUT`, `EASE_IN_OUT`, `SMOOTHSTEP`). |
| `Transform2D` | `final class` | Veränderbarer 2D-Transformationszustand für Sprites und Szenengraph-Knoten (Position/Anchor/Align, Zoom, Rotation, Alpha, Blur, `ColorMatrix4f`, Crop, Parallax). |
| `ScreenShake` | `final class` | Gedämpfter harmonischer Oszillator (`vpunch`, `hpunch`, `custom`) für zeitabhängige Kamera-/Bildschirm-Offsets. |

## Öffentliche API-Signaturen

### `Vec2f`
```java
public record Vec2f(float x, float y)
public static final Vec2f ZERO
public static final Vec2f ONE
public static final Vec2f HALF

public Vec2f add(Vec2f other)
public Vec2f sub(Vec2f other)
public Vec2f scale(float factor)
public Vec2f lerp(Vec2f target, float t)
public float length()
public float distance(Vec2f other)
```

### `Rect2f`
```java
public record Rect2f(float x, float y, float width, float height)

public float right()
public float bottom()
public float centerX()
public float centerY()
public boolean contains(float px, float py)
public boolean intersects(Rect2f other)
public Rect2f translate(float dx, float dy)
public Rect2f inset(float amount)
```

### `ColorMatrix4f`
```java
public final class ColorMatrix4f
public static final float LUMA_R = 0.2126f
public static final float LUMA_G = 0.7152f
public static final float LUMA_B = 0.0722f

public static ColorMatrix4f identity()
public static ColorMatrix4f ofColumnMajor(float[] m)
public static ColorMatrix4f ofRowMajor(float[] m)
public static ColorMatrix4f brightness(float b)
public static ColorMatrix4f contrast(float c)
public static ColorMatrix4f saturation(float s)
public static ColorMatrix4f hueRotate(float degrees)
public static ColorMatrix4f tint(float r, float g, float b)
public static ColorMatrix4f opacity(float a)
public static ColorMatrix4f invert()

public ColorMatrix4f multiply(ColorMatrix4f other)
public ColorMatrix4f lerp(ColorMatrix4f target, float t)
public float[] toFloatArray()
public float[] applyPremultiplied(float rPre, float gPre, float bPre, float a)
```

### `Easing` & `Easing.EaseType`
```java
public final class Easing
public enum EaseType { LINEAR, EASE, EASE_IN, EASE_OUT, EASE_IN_OUT, SMOOTHSTEP }

public static float linear(float t)
public static float ease(float t)
public static float easeIn(float t)
public static float easeOut(float t)
public static float easeInOut(float t)
public static float smoothstep(float t)
public static float cubicBezier(float t, float p1x, float p1y, float p2x, float p2y)
public static float evaluate(EaseType type, float t)
```

### `Transform2D`
```java
public final class Transform2D
public Transform2D()

public float getXpos()
public Transform2D setXpos(float xpos)
public float getYpos()
public Transform2D setYpos(float ypos)
public float getXanchor()
public Transform2D setXanchor(float xanchor)
public float getYanchor()
public Transform2D setYanchor(float yanchor)
public float getXalign()
public Transform2D setXalign(float xalign)
public boolean hasXalign()
public float getYalign()
public Transform2D setYalign(float yalign)
public boolean hasYalign()
public float getZoom()
public Transform2D setZoom(float zoom)
public float getXzoom()
public Transform2D setXzoom(float xzoom)
public float getYzoom()
public Transform2D setYzoom(float yzoom)
public float getRotate()
public Transform2D setRotate(float rotate)
public float getAlpha()
public Transform2D setAlpha(float alpha)
public float getBlur()
public Transform2D setBlur(float blur)
public ColorMatrix4f getColorMatrix()
public Transform2D setColorMatrix(ColorMatrix4f colorMatrix)
public Rect2f getCrop()
public Transform2D setCrop(Rect2f crop)
public float getParallaxX()
public Transform2D setParallaxX(float parallaxX)
public float getParallaxY()
public Transform2D setParallaxY(float parallaxY)
public Transform2D copy()
public Transform2D lerpTo(Transform2D target, float t)
public Rect2f resolveBounds(float width, float height, float containerWidth, float containerHeight)
```

### `ScreenShake`
```java
public final class ScreenShake
public static final float DEFAULT_AMPLITUDE = 30.0f
public static final float DEFAULT_DECAY = 5.0f
public static final float DEFAULT_OMEGA = 40.0f
public static final float DEFAULT_DURATION_SECONDS = 1.0f

public static ScreenShake vpunch()
public static ScreenShake hpunch()
public static ScreenShake custom(float ampX, float ampY, float decay, float omega, float durationSeconds)

public void update(float dt)
public Vec2f getOffset()
public boolean isFinished()
public void reset()
public float getAmplitudeX()
public float getAmplitudeY()
public float getDecay()
public float getOmega()
public float getDurationSeconds()
public float getElapsed()
```
