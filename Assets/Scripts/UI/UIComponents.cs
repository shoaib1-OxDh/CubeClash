using UnityEngine;
using UnityEngine.EventSystems;
using UnityEngine.UI;

namespace CubeSnake.UI
{
    /// <summary>Keeps children inside Screen.safeArea (notches, rounded corners, gesture bars).</summary>
    [RequireComponent(typeof(RectTransform))]
    public sealed class SafeAreaFitter : MonoBehaviour
    {
        Rect _applied;
        Vector2Int _screen;

        void OnEnable() => Apply();

        void Update()
        {
            if (_applied != Screen.safeArea || _screen.x != Screen.width || _screen.y != Screen.height) Apply();
        }

        void Apply()
        {
            var rt = (RectTransform)transform;
            Rect safe = Screen.safeArea;
            _applied = safe;
            _screen = new Vector2Int(Screen.width, Screen.height);
            if (Screen.width <= 0 || Screen.height <= 0) return;
            // Extra margin from the very edges so controls stay clear of system gestures.
            float margin = Mathf.Min(Screen.width, Screen.height) * 0.015f;
            Vector2 min = new Vector2((safe.xMin + margin) / Screen.width, (safe.yMin + margin) / Screen.height);
            Vector2 max = new Vector2((safe.xMax - margin) / Screen.width, (safe.yMax - margin) / Screen.height);
            rt.anchorMin = min;
            rt.anchorMax = max;
            rt.offsetMin = rt.offsetMax = Vector2.zero;
        }
    }

    /// <summary>Switches the CanvasScaler reference between portrait and landscape so UI stays readable on rotation.</summary>
    [RequireComponent(typeof(CanvasScaler))]
    public sealed class CanvasMatchAdapter : MonoBehaviour
    {
        CanvasScaler _scaler;
        bool _landscape;
        bool _init;

        void Awake() => _scaler = GetComponent<CanvasScaler>();

        void Update()
        {
            bool landscape = Screen.width > Screen.height;
            if (_init && landscape == _landscape) return;
            _init = true;
            _landscape = landscape;
            _scaler.referenceResolution = landscape ? new Vector2(1920, 1080) : new Vector2(1080, 1920);
            _scaler.matchWidthOrHeight = landscape ? 0.65f : 0.35f;
        }
    }

    /// <summary>Shrinks a fixed-size card uniformly if it does not fit its parent (small or landscape screens).</summary>
    public sealed class FitInsideParent : MonoBehaviour
    {
        void LateUpdate()
        {
            var rt = (RectTransform)transform;
            var parent = rt.parent as RectTransform;
            if (parent == null) return;
            Vector2 avail = parent.rect.size * 0.96f;
            Vector2 size = rt.sizeDelta;
            if (size.x <= 0 || size.y <= 0) return;
            float s = Mathf.Min(1f, Mathf.Min(avail.x / size.x, avail.y / size.y));
            rt.localScale = new Vector3(s, s, 1f);
        }
    }

    /// <summary>Small press feedback for buttons.</summary>
    public sealed class ButtonPressScale : MonoBehaviour, IPointerDownHandler, IPointerUpHandler, IPointerExitHandler
    {
        float _target = 1f;
        void Update()
        {
            float s = Mathf.Lerp(transform.localScale.x, _target, 1f - Mathf.Exp(-25f * Time.unscaledDeltaTime));
            transform.localScale = new Vector3(s, s, 1f);
        }
        public void OnPointerDown(PointerEventData e) => _target = 0.94f;
        public void OnPointerUp(PointerEventData e) => _target = 1f;
        public void OnPointerExit(PointerEventData e) => _target = 1f;
        void OnDisable() { _target = 1f; transform.localScale = Vector3.one; }
    }

    /// <summary>A button that reports whether it is currently held (boost). Multi-touch safe.</summary>
    public sealed class HoldButton : MonoBehaviour, IPointerDownHandler, IPointerUpHandler
    {
        int _pointer = int.MinValue;
        public bool IsHeld => _pointer != int.MinValue;

        public void OnPointerDown(PointerEventData e)
        {
            _pointer = e.pointerId;
        }

        public void OnPointerUp(PointerEventData e)
        {
            if (e.pointerId == _pointer) _pointer = int.MinValue;
        }

        void OnDisable() => _pointer = int.MinValue;
        void OnApplicationPause(bool paused) { if (paused) _pointer = int.MinValue; }
    }

    /// <summary>
    /// Floating analog joystick: touching anywhere inside its zone re-centres the base under the finger, dragging
    /// gives a smooth analog vector (magnitude 0..1), and releasing returns it to neutral.
    /// </summary>
    public sealed class VirtualJoystick : MonoBehaviour, IPointerDownHandler, IDragHandler, IPointerUpHandler
    {
        public RectTransform Base;
        public RectTransform Knob;
        public float Radius = 150f;

        int _pointer = int.MinValue;
        Vector2 _baseRest;
        Canvas _canvas;

        public Vector2 Value { get; private set; }
        public bool IsActive => _pointer != int.MinValue;

        void Start()
        {
            _canvas = GetComponentInParent<Canvas>();
            if (Base != null) _baseRest = Base.anchoredPosition;
        }

        Camera EventCamera => _canvas != null && _canvas.renderMode != RenderMode.ScreenSpaceOverlay ? _canvas.worldCamera : null;

        public void OnPointerDown(PointerEventData e)
        {
            if (_pointer != int.MinValue) return;
            _pointer = e.pointerId;
            if (Base == null) return;
            var zone = (RectTransform)Base.parent;
            if (RectTransformUtility.ScreenPointToLocalPointInRectangle(zone, e.position, EventCamera, out var local))
            {
                // Convert from the zone's pivot space into the base's anchor space.
                Rect r = zone.rect;
                Vector2 anchorRef = r.min + Vector2.Scale(r.size, Base.anchorMin);
                Base.anchoredPosition = local - anchorRef;
            }
            OnDrag(e);
        }

        public void OnDrag(PointerEventData e)
        {
            if (e.pointerId != _pointer || Base == null) return;
            if (!RectTransformUtility.ScreenPointToLocalPointInRectangle(Base, e.position, EventCamera, out var local)) return;
            float r = Radius; // local units; the base is scaled visually by the joystick-size setting
            Vector2 v = Vector2.ClampMagnitude(local, r);
            Knob.anchoredPosition = v;
            Value = v / r;
        }

        public void OnPointerUp(PointerEventData e)
        {
            if (e.pointerId != _pointer) return;
            Release();
        }

        void Release()
        {
            _pointer = int.MinValue;
            Value = Vector2.zero;
            if (Knob != null) Knob.anchoredPosition = Vector2.zero;
            if (Base != null) Base.anchoredPosition = _baseRest;
        }

        void OnDisable() => Release();
        void OnApplicationPause(bool paused) { if (paused) Release(); }

        void Update()
        {
            float s = Core.SettingsManager.JoystickScale;
            if (Base != null) Base.localScale = new Vector3(s, s, 1f);
        }
    }

    /// <summary>Invisible full-screen touch area for the tap-to-steer control scheme.</summary>
    public sealed class SteerArea : MonoBehaviour, IPointerDownHandler, IDragHandler, IPointerUpHandler
    {
        int _pointer = int.MinValue;
        public bool IsPressed => _pointer != int.MinValue;
        public Vector2 PointerPosition { get; private set; }

        public void OnPointerDown(PointerEventData e)
        {
            if (_pointer != int.MinValue) return;
            _pointer = e.pointerId;
            PointerPosition = e.position;
        }

        public void OnDrag(PointerEventData e)
        {
            if (e.pointerId == _pointer) PointerPosition = e.position;
        }

        public void OnPointerUp(PointerEventData e)
        {
            if (e.pointerId == _pointer) _pointer = int.MinValue;
        }

        void OnDisable() => _pointer = int.MinValue;
    }
}
