using System;

namespace CubeSnake.Logic
{
    /// <summary>
    /// Engine-independent 2D vector on the ground plane. X maps to world X and Y maps to world Z.
    /// Kept free of UnityEngine so that all gameplay rules can be unit tested outside the editor.
    /// </summary>
    [Serializable]
    public struct Vec2 : IEquatable<Vec2>
    {
        public float X;
        public float Y;

        public Vec2(float x, float y) { X = x; Y = y; }

        public static readonly Vec2 Zero = new Vec2(0f, 0f);
        public static readonly Vec2 UnitX = new Vec2(1f, 0f);
        public static readonly Vec2 UnitY = new Vec2(0f, 1f);

        public float LengthSquared => X * X + Y * Y;
        public float Length => (float)Math.Sqrt(X * X + Y * Y);

        public Vec2 Normalized
        {
            get
            {
                float len = Length;
                return len > 1e-6f ? new Vec2(X / len, Y / len) : Zero;
            }
        }

        /// <summary>Angle in radians measured from +X towards +Y.</summary>
        public float Angle => (float)Math.Atan2(Y, X);

        public static Vec2 FromAngle(float radians) => new Vec2((float)Math.Cos(radians), (float)Math.Sin(radians));

        public static Vec2 operator +(Vec2 a, Vec2 b) => new Vec2(a.X + b.X, a.Y + b.Y);
        public static Vec2 operator -(Vec2 a, Vec2 b) => new Vec2(a.X - b.X, a.Y - b.Y);
        public static Vec2 operator -(Vec2 a) => new Vec2(-a.X, -a.Y);
        public static Vec2 operator *(Vec2 a, float s) => new Vec2(a.X * s, a.Y * s);
        public static Vec2 operator *(float s, Vec2 a) => new Vec2(a.X * s, a.Y * s);
        public static Vec2 operator /(Vec2 a, float s) => new Vec2(a.X / s, a.Y / s);

        public static float Dot(Vec2 a, Vec2 b) => a.X * b.X + a.Y * b.Y;
        public static float Cross(Vec2 a, Vec2 b) => a.X * b.Y - a.Y * b.X;
        public static float Distance(Vec2 a, Vec2 b) => (a - b).Length;
        public static float DistanceSquared(Vec2 a, Vec2 b) => (a - b).LengthSquared;
        public static Vec2 Lerp(Vec2 a, Vec2 b, float t) => new Vec2(a.X + (b.X - a.X) * t, a.Y + (b.Y - a.Y) * t);
        public Vec2 Perpendicular => new Vec2(-Y, X);

        public bool Equals(Vec2 other) => X.Equals(other.X) && Y.Equals(other.Y);
        public override bool Equals(object obj) => obj is Vec2 v && Equals(v);
        public override int GetHashCode() => (X.GetHashCode() * 397) ^ Y.GetHashCode();
        public override string ToString() => $"({X:0.###}, {Y:0.###})";
    }

    public static class MathUtil
    {
        public const float Pi = (float)Math.PI;
        public const float TwoPi = (float)(Math.PI * 2.0);

        public static float Clamp(float v, float min, float max) => v < min ? min : (v > max ? max : v);
        public static int Clamp(int v, int min, int max) => v < min ? min : (v > max ? max : v);
        public static float Clamp01(float v) => Clamp(v, 0f, 1f);
        public static float Lerp(float a, float b, float t) => a + (b - a) * t;

        /// <summary>Wraps an angle into (-PI, PI].</summary>
        public static float WrapAngle(float radians)
        {
            radians %= TwoPi;
            if (radians <= -Pi) radians += TwoPi;
            else if (radians > Pi) radians -= TwoPi;
            return radians;
        }

        /// <summary>Rotates <paramref name="current"/> towards <paramref name="target"/> by at most maxDelta radians.</summary>
        public static float RotateTowards(float current, float target, float maxDelta)
        {
            float delta = WrapAngle(target - current);
            if (Math.Abs(delta) <= maxDelta) return WrapAngle(target);
            return WrapAngle(current + Math.Sign(delta) * maxDelta);
        }

        public static float MoveTowards(float current, float target, float maxDelta)
        {
            if (Math.Abs(target - current) <= maxDelta) return target;
            return current + Math.Sign(target - current) * maxDelta;
        }

        /// <summary>Frame-rate independent exponential smoothing factor.</summary>
        public static float SmoothFactor(float sharpness, float dt) => 1f - (float)Math.Exp(-sharpness * dt);
    }
}
