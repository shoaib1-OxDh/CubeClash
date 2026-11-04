using UnityEngine;

namespace CubeSnake.Player
{
    /// <summary>
    /// Keyboard / gamepad-free fallback input for the editor and desktop testing, and the Android back button.
    /// Uses the Input System package when it is active and falls back to the legacy Input Manager otherwise.
    /// </summary>
    public static class KeyboardInput
    {
        public static Vector2 Move()
        {
            Vector2 v = Vector2.zero;
#if CUBESNAKE_INPUT_SYSTEM && ENABLE_INPUT_SYSTEM
            var kb = UnityEngine.InputSystem.Keyboard.current;
            if (kb != null)
            {
                if (kb.aKey.isPressed || kb.leftArrowKey.isPressed) v.x -= 1;
                if (kb.dKey.isPressed || kb.rightArrowKey.isPressed) v.x += 1;
                if (kb.sKey.isPressed || kb.downArrowKey.isPressed) v.y -= 1;
                if (kb.wKey.isPressed || kb.upArrowKey.isPressed) v.y += 1;
            }
            var pad = UnityEngine.InputSystem.Gamepad.current;
            if (pad != null)
            {
                Vector2 stick = pad.leftStick.ReadValue();
                if (stick.sqrMagnitude > v.sqrMagnitude) v = stick;
            }
#elif ENABLE_LEGACY_INPUT_MANAGER
            if (Input.GetKey(KeyCode.A) || Input.GetKey(KeyCode.LeftArrow)) v.x -= 1;
            if (Input.GetKey(KeyCode.D) || Input.GetKey(KeyCode.RightArrow)) v.x += 1;
            if (Input.GetKey(KeyCode.S) || Input.GetKey(KeyCode.DownArrow)) v.y -= 1;
            if (Input.GetKey(KeyCode.W) || Input.GetKey(KeyCode.UpArrow)) v.y += 1;
#endif
            return v.sqrMagnitude > 1f ? v.normalized : v;
        }

        public static bool Boost()
        {
#if CUBESNAKE_INPUT_SYSTEM && ENABLE_INPUT_SYSTEM
            var kb = UnityEngine.InputSystem.Keyboard.current;
            var pad = UnityEngine.InputSystem.Gamepad.current;
            return (kb != null && (kb.spaceKey.isPressed || kb.leftShiftKey.isPressed)) || (pad != null && pad.buttonSouth.isPressed);
#elif ENABLE_LEGACY_INPUT_MANAGER
            return Input.GetKey(KeyCode.Space) || Input.GetKey(KeyCode.LeftShift);
#else
            return false;
#endif
        }

        /// <summary>Escape on desktop, Back on Android.</summary>
        public static bool BackPressed()
        {
#if CUBESNAKE_INPUT_SYSTEM && ENABLE_INPUT_SYSTEM
            var kb = UnityEngine.InputSystem.Keyboard.current;
            var pad = UnityEngine.InputSystem.Gamepad.current;
            return (kb != null && kb.escapeKey.wasPressedThisFrame) || (pad != null && pad.startButton.wasPressedThisFrame);
#elif ENABLE_LEGACY_INPUT_MANAGER
            return Input.GetKeyDown(KeyCode.Escape);
#else
            return false;
#endif
        }
    }
}
