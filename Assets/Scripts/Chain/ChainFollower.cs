using CubeSnake.Logic;
using UnityEngine;

namespace CubeSnake.Chain
{
    /// <summary>
    /// Places a cube along its chain's recorded path. Each cube has a target arc distance behind the head; the
    /// current distance eases towards it so insertions and merges slide smoothly instead of popping.
    /// </summary>
    public sealed class ChainFollower : MonoBehaviour
    {
        public float CurrentDistance { get; set; }
        public float TargetDistance { get; set; }

        public void Snap(float distance)
        {
            CurrentDistance = distance;
            TargetDistance = distance;
        }

        public void Follow(PathTrail trail, float sharpness, float dt, float bob)
        {
            CurrentDistance = Mathf.Lerp(CurrentDistance, TargetDistance, MathUtil.SmoothFactor(sharpness, dt));
            Vec2 p = trail.Sample(CurrentDistance);
            Vec2 f = trail.SampleForward(CurrentDistance);
            transform.position = new Vector3(p.X, bob, p.Y);
            var forward = new Vector3(f.X, 0f, f.Y);
            if (forward.sqrMagnitude > 1e-6f) transform.rotation = Quaternion.LookRotation(forward, Vector3.up);
        }
    }
}
