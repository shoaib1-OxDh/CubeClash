using System;

namespace CubeSnake.Logic
{
    /// <summary>
    /// Records the path travelled by a chain's head as evenly spaced points and samples positions at an arc
    /// distance behind the head in O(1). Every following cube is placed at a fixed distance along this path,
    /// which keeps the chain stable through sharp turns, full circles and reversals (cubes retrace the exact
    /// path instead of cutting corners).
    /// </summary>
    public sealed class PathTrail
    {
        Vec2[] _points;   // ring buffer, newest at _newest
        int _newest;
        int _count;
        readonly float _spacing;

        public Vec2 Head { get; private set; }
        public float Spacing => _spacing;
        public int Count => _count;
        public int Capacity => _points.Length;

        /// <summary>Total length of path currently stored behind the head.</summary>
        public float StoredLength => _count == 0 ? 0f : Vec2.Distance(Head, _points[_newest]) + (_count - 1) * _spacing;

        public PathTrail(float spacing, float initialLength)
        {
            if (spacing <= 0) throw new ArgumentOutOfRangeException(nameof(spacing));
            _spacing = spacing;
            int cap = Math.Max(8, (int)Math.Ceiling(initialLength / spacing) + 4);
            _points = new Vec2[cap];
        }

        /// <summary>Resets the trail to a straight line behind <paramref name="head"/> pointing along <paramref name="forward"/>.</summary>
        public void Reset(Vec2 head, Vec2 forward, float length)
        {
            EnsureLength(length);
            Vec2 back = -(forward.LengthSquared > 1e-8f ? forward.Normalized : Vec2.UnitY);
            Head = head;
            _count = 0;
            _newest = -1;
            int n = Math.Min(_points.Length, (int)Math.Ceiling(length / _spacing) + 2);
            // Push oldest first so that the newest point ends up exactly at the head.
            for (int i = n - 1; i >= 0; i--) PushPoint(head + back * (i * _spacing));
        }

        /// <summary>Makes sure at least <paramref name="length"/> of path can be stored (grows the buffer, keeps data).</summary>
        public void EnsureLength(float length)
        {
            int needed = (int)Math.Ceiling(length / _spacing) + 4;
            if (needed <= _points.Length) return;
            int newCap = Math.Max(needed, _points.Length * 2);
            var copy = new Vec2[newCap];
            // Copy oldest -> newest into the new buffer.
            for (int k = _count - 1, j = 0; k >= 0; k--, j++) copy[j] = PointAt(k);
            _points = copy;
            _newest = _count - 1;
        }

        void PushPoint(Vec2 p)
        {
            _newest = (_newest + 1) % _points.Length;
            _points[_newest] = p;
            if (_count < _points.Length) _count++;
        }

        /// <summary>k = 0 is the newest recorded point, k = Count-1 the oldest.</summary>
        public Vec2 PointAt(int k)
        {
            int idx = _newest - k;
            if (idx < 0) idx += _points.Length;
            return _points[idx];
        }

        /// <summary>Moves the head to <paramref name="newHead"/>, recording points every <see cref="Spacing"/> units.</summary>
        public void MoveHead(Vec2 newHead)
        {
            Head = newHead;
            if (_count == 0)
            {
                PushPoint(newHead);
                return;
            }
            Vec2 last = _points[_newest];
            float dist = Vec2.Distance(last, newHead);
            // Guard against huge teleports: re-seed instead of pushing thousands of points.
            if (dist > _points.Length * _spacing)
            {
                Reset(newHead, newHead - last, (_count - 1) * _spacing);
                return;
            }
            while (dist >= _spacing)
            {
                Vec2 dir = (newHead - last) / dist;
                last = last + dir * _spacing;
                PushPoint(last);
                dist = Vec2.Distance(last, newHead);
            }
        }

        /// <summary>Position at arc distance <paramref name="distance"/> behind the head (clamped to the stored path).</summary>
        public Vec2 Sample(float distance)
        {
            if (_count == 0 || distance <= 0f) return Head;
            Vec2 newest = _points[_newest];
            float d0 = Vec2.Distance(Head, newest);
            if (distance <= d0) return d0 > 1e-6f ? Vec2.Lerp(Head, newest, distance / d0) : newest;
            float rem = (distance - d0) / _spacing;
            int k = (int)rem;
            if (k >= _count - 1) return PointAt(_count - 1);
            float t = rem - k;
            return Vec2.Lerp(PointAt(k), PointAt(k + 1), t);
        }

        /// <summary>Unit direction of travel at <paramref name="distance"/> behind the head.</summary>
        public Vec2 SampleForward(float distance, float window = 0.35f)
        {
            Vec2 a = Sample(Math.Max(0f, distance - window));
            Vec2 b = Sample(distance + window);
            Vec2 d = a - b;
            return d.LengthSquared > 1e-8f ? d.Normalized : Vec2.UnitY;
        }
    }
}
