using CubeSnake.Chain;
using CubeSnake.Core;
using UnityEngine;

namespace CubeSnake.CameraSystem
{
    /// <summary>
    /// Elevated third-person follow camera. Keeps the leading cube in view with a slight look-ahead, zooms out as the
    /// chain grows, adapts to portrait/landscape aspect ratios, never dips below the ground, and supports a fixed
    /// angle (default, steering is screen-relative) or a mode that slowly rotates behind the heading.
    /// </summary>
    [RequireComponent(typeof(UnityEngine.Camera))]
    public sealed class FollowCameraController : MonoBehaviour
    {
        public CubeChainController Target;
        public CameraConfig Settings;

        UnityEngine.Camera _cam;
        Vector3 _velocity;
        Vector3 _focus;
        float _yaw;
        float _yawVelocity;
        float _shake;
        float _zoomSmoothed = 1f;

        public UnityEngine.Camera Camera => _cam;

        void Awake()
        {
            _cam = GetComponent<UnityEngine.Camera>();
        }

        public void Snap()
        {
            if (Target == null || Settings == null) return;
            _focus = ArenaContext.ToWorld(Target.HeadPosition);
            _yaw = SettingsManager.Camera == CameraMode.RotateWithHeading ? HeadingYaw() : 0f;
            transform.position = DesiredPosition(_focus, ComputeZoom());
            transform.LookAt(_focus);
            _velocity = Vector3.zero;
        }

        public void AddShake(float amount)
        {
            if (SettingsManager.LowEffects) amount *= 0.5f;
            _shake = Mathf.Min(1f, _shake + amount);
        }

        float HeadingYaw()
        {
            Vector3 f = new Vector3(Target.Forward.X, 0f, Target.Forward.Y);
            return Mathf.Atan2(f.x, f.z) * Mathf.Rad2Deg;
        }

        float ComputeZoom()
        {
            float chainZoom = 1f + Mathf.Min(Settings.maxChainZoom - 1f, Target.Model.Length * Settings.zoomPerCube + Target.HeadLevel * 0.02f);
            float aspect = _cam != null ? _cam.aspect : 1f;
            // Portrait screens see less horizontally: pull back so a similar area stays visible.
            float aspectZoom = aspect < 1f ? Mathf.Lerp(1.25f, 1f, Mathf.InverseLerp(0.45f, 1f, aspect)) : 1f;
            return chainZoom * aspectZoom * SettingsManager.CameraZoom;
        }

        Vector3 DesiredPosition(Vector3 focus, float zoom)
        {
            Quaternion yawRot = Quaternion.Euler(0f, _yaw, 0f);
            Vector3 offset = yawRot * new Vector3(0f, Settings.height, -Settings.distance) * zoom;
            Vector3 pos = focus + offset;
            if (pos.y < Settings.minHeight) pos.y = Settings.minHeight;
            return pos;
        }

        void LateUpdate()
        {
            if (Target == null || Settings == null) return;
            float dt = Time.unscaledDeltaTime;
            if (dt <= 0f) return;
            float sens = SettingsManager.CameraSensitivity;

            Vector3 head = ArenaContext.ToWorld(Target.HeadPosition);
            Vector3 ahead = new Vector3(Target.Velocity.X, 0f, Target.Velocity.Y) * (Settings.lookAhead / Mathf.Max(1f, Target.Speed + 0.001f));
            Vector3 desiredFocus = head + ahead;
            _focus = Vector3.Lerp(_focus, desiredFocus, 1f - Mathf.Exp(-8f * sens * dt));

            if (SettingsManager.Camera == CameraMode.RotateWithHeading)
                _yaw = Mathf.SmoothDampAngle(_yaw, HeadingYaw(), ref _yawVelocity, Settings.yawSmoothTime / sens, Mathf.Infinity, dt);
            else
                _yaw = Mathf.SmoothDampAngle(_yaw, 0f, ref _yawVelocity, 0.3f, Mathf.Infinity, dt);

            _zoomSmoothed = Mathf.Lerp(_zoomSmoothed, ComputeZoom(), 1f - Mathf.Exp(-2f * dt));
            Vector3 desired = DesiredPosition(_focus, _zoomSmoothed);
            Vector3 pos = Vector3.SmoothDamp(transform.position, desired, ref _velocity, Settings.positionSmoothTime / sens, Mathf.Infinity, dt);

            if (_shake > 0f)
            {
                float t = Time.unscaledTime * 35f;
                pos += new Vector3(Mathf.PerlinNoise(t, 0.1f) - 0.5f, Mathf.PerlinNoise(0.3f, t) - 0.5f, 0f) * (_shake * 0.6f);
                _shake = Mathf.Max(0f, _shake - Settings.shakeDecay * dt);
            }
            if (pos.y < Settings.minHeight) pos.y = Settings.minHeight;
            transform.position = pos;
            transform.rotation = Quaternion.LookRotation(_focus - pos, Vector3.up);
            _cam.fieldOfView = Settings.fieldOfView;
        }
    }
}
