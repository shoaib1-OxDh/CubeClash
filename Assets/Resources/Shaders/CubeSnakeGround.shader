// Procedural ground: anti-aliased grid computed from world position (no texture), soft radial vignette and a
// glowing boundary ring at _ArenaRadius. Pipeline-agnostic.
Shader "CubeSnake/Ground"
{
    Properties
    {
        _Color ("Ground", Color) = (0.86,0.9,0.97,1)
        _GridColor ("Grid", Color) = (0.7,0.77,0.92,1)
        _OutsideColor ("Outside", Color) = (0.6,0.66,0.84,1)
        _BoundaryColor ("Boundary", Color) = (1,0.36,0.42,1)
        _GridSize ("Grid Size", Float) = 4
        _LineWidth ("Line Width", Range(0.001, 0.2)) = 0.04
        _ArenaRadius ("Arena Radius", Float) = 120
    }
    SubShader
    {
        Tags { "RenderType"="Opaque" "Queue"="Geometry-10" }
        Pass
        {
            CGPROGRAM
            #pragma vertex vert
            #pragma fragment frag
            #pragma multi_compile_fog
            #include "UnityCG.cginc"

            fixed4 _Color, _GridColor, _OutsideColor, _BoundaryColor;
            float _GridSize, _LineWidth, _ArenaRadius;

            struct appdata { float4 vertex : POSITION; };
            struct v2f { float4 pos : SV_POSITION; float3 world : TEXCOORD0; UNITY_FOG_COORDS(1) };

            v2f vert (appdata v)
            {
                v2f o;
                o.pos = UnityObjectToClipPos(v.vertex);
                o.world = mul(unity_ObjectToWorld, v.vertex).xyz;
                UNITY_TRANSFER_FOG(o, o.pos);
                return o;
            }

            fixed4 frag (v2f i) : SV_Target
            {
                float2 p = i.world.xz / _GridSize;
                float2 fw = max(fwidth(p), 1e-4);
                float2 d = abs(frac(p - 0.5) - 0.5);
                float2 l = 1 - smoothstep(_LineWidth - fw, _LineWidth + fw, d);
                float lineMask = max(l.x, l.y);
                // Major lines every 4 cells.
                float2 p4 = p * 0.25;
                float2 fw4 = max(fwidth(p4), 1e-4);
                float2 d4 = abs(frac(p4 - 0.5) - 0.5);
                float2 l4 = 1 - smoothstep(_LineWidth * 0.5 - fw4, _LineWidth * 0.5 + fw4, d4);
                float major = max(l4.x, l4.y);

                float r = length(i.world.xz);
                fixed4 baseCol = r < _ArenaRadius ? _Color : _OutsideColor;
                fixed4 col = lerp(baseCol, _GridColor, saturate(lineMask * 0.55 + major * 0.35));
                // Gentle vignette towards the edge.
                col.rgb *= lerp(1.0, 0.88, saturate(r / _ArenaRadius));
                // Boundary band.
                float band = 1 - saturate(abs(r - _ArenaRadius) / 1.2);
                float warn = saturate(1 - (_ArenaRadius - r) / 12) * (r < _ArenaRadius ? 1 : 0);
                col.rgb = lerp(col.rgb, _BoundaryColor.rgb, saturate(band + warn * 0.25));
                UNITY_APPLY_FOG(i.fogCoord, col);
                return col;
            }
            ENDCG
        }
    }
    FallBack Off
}
