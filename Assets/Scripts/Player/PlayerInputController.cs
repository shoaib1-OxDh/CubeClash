using CubeSnake.Core;
using CubeSnake.UI;
using UnityEngine;

namespace CubeSnake.Player
{
    /// <summary>
    /// Collects steering and boost input from the on-screen joystick, tap-to-steer area, boost button and keyboard,
    /// and converts it into a world-space direction relative to the camera.
    /// </summary>
    public sealed class PlayerInputController : MonoBehaviour
    {
        public VirtualJoystick Joystick;
        public HoldButton BoostButton;
        public SteerArea SteerArea;
        public Camera Camera;

        public const float DeadZone = 0.12f;

        public bool BoostHeld => (BoostButton != null && BoostButton.IsHeld) || KeyboardInput.Boost();

        /// <summary>
        /// World-space (XZ) steering direction for a chain whose head is at <paramref name="headWorld"/>.
        /// Returns false when there is no steering input (the chain keeps its heading).
        /// </summary>
        public bool TryGetWorldDirection(Vector3 headWorld, out Vector3 dir)
        {
            dir = Vector3.zero;
            Vector2 screenDir = Vector2.zero;
            bool has = false;

            if (Joystick != null && Joystick.isActiveAndEnabled && Joystick.Value.magnitude > DeadZone)
            {
                screenDir = Joystick.Value;
                has = true;
            }
            else if (SettingsManager.Controls == ControlScheme.TapToSteer && SteerArea != null && SteerArea.IsPressed && Camera != null)
            {
                Vector3 headScreen = Camera.WorldToScreenPoint(headWorld);
                Vector2 delta = SteerArea.PointerPosition - new Vector2(headScreen.x, headScreen.y);
                if (delta.sqrMagnitude > 400f) // ignore taps right on the head (20px)
                {
                    screenDir = delta.normalized;
                    has = true;
                }
            }
            else
            {
                Vector2 kb = KeyboardInput.Move();
                if (kb.sqrMagnitude > 0.01f) { screenDir = kb; has = true; }
            }

            if (!has) return false;
            dir = ScreenToWorld(screenDir);
            return dir.sqrMagnitude > 1e-6f;
        }

        Vector3 ScreenToWorld(Vector2 screenDir)
        {
            if (Camera == null) return new Vector3(screenDir.x, 0f, screenDir.y).normalized;
            Vector3 fwd = Camera.transform.forward; fwd.y = 0f;
            Vector3 right = Camera.transform.right; right.y = 0f;
            if (fwd.sqrMagnitude < 1e-4f) fwd = Camera.transform.up; // looking straight down
            fwd.y = 0f;
            fwd.Normalize();
            right.Normalize();
            return (right * screenDir.x + fwd * screenDir.y).normalized;
        }
    }
}
