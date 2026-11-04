// Lightweight lit shader for numbered cubes. Pipeline-agnostic (no LightMode tag), so it renders identically in
// URP and the built-in pipeline. Lighting is a single directional light supplied through global properties
// (_CS_LightDir/_CS_LightColor/_CS_Ambient) set by ArenaBuilder, plus rim light and optional emission (glow).
Shader "CubeSnake/Cube"
{
    Properties
    {
        _MainTex ("Face Texture", 2D) = "white" {}
        _Color ("Tint", Color) = (1,1,1,1)
        _Emission ("Emission Strength", Range(0, 2)) = 0
        _EmissionColor ("Emission Color", Color) = (1,1,1,1)
        _Rim ("Rim Strength", Range(0, 1)) = 0.25
    }
    SubShader
    {
        Tags { "RenderType"="Opaque" "Queue"="Geometry" }
        LOD 100
        Pass
        {
            CGPROGRAM
            #pragma vertex vert
            #pragma fragment frag
            #pragma multi_compile_fog
            #pragma multi_compile_instancing
            #include "UnityCG.cginc"

            sampler2D _MainTex;
            float4 _MainTex_ST;
            fixed4 _Color;
            half _Emission;
            fixed4 _EmissionColor;
            half _Rim;
            float4 _CS_LightDir;
            fixed4 _CS_LightColor;
            fixed4 _CS_Ambient;

            struct appdata { float4 vertex : POSITION; float3 normal : NORMAL; float2 uv : TEXCOORD0; UNITY_VERTEX_INPUT_INSTANCE_ID };
            struct v2f
            {
                float4 pos : SV_POSITION;
                float2 uv : TEXCOORD0;
                half3 lighting : TEXCOORD1;
                half rim : TEXCOORD2;
                UNITY_FOG_COORDS(3)
            };

            v2f vert (appdata v)
            {
                v2f o;
                UNITY_SETUP_INSTANCE_ID(v);
                o.pos = UnityObjectToClipPos(v.vertex);
                o.uv = TRANSFORM_TEX(v.uv, _MainTex);
                float3 n = normalize(UnityObjectToWorldNormal(v.normal));
                float3 l = dot(_CS_LightDir.xyz, _CS_LightDir.xyz) > 0.001 ? normalize(_CS_LightDir.xyz) : normalize(float3(0.35, 0.85, -0.4));
                fixed3 lightCol = dot(_CS_LightColor.rgb, _CS_LightColor.rgb) > 0.001 ? _CS_LightColor.rgb : fixed3(0.85, 0.85, 0.8);
                fixed3 ambient = dot(_CS_Ambient.rgb, _CS_Ambient.rgb) > 0.001 ? _CS_Ambient.rgb : fixed3(0.48, 0.5, 0.58);
                half ndl = saturate(dot(n, l));
                // Soft wrap lighting keeps the shadow side readable.
                half wrap = saturate((dot(n, l) + 0.4) / 1.4);
                o.lighting = ambient + lightCol * (ndl * 0.6 + wrap * 0.4);
                float3 worldPos = mul(unity_ObjectToWorld, v.vertex).xyz;
                float3 viewDir = normalize(_WorldSpaceCameraPos - worldPos);
                o.rim = pow(1 - saturate(dot(n, viewDir)), 3) * _Rim;
                UNITY_TRANSFER_FOG(o, o.pos);
                return o;
            }

            fixed4 frag (v2f i) : SV_Target
            {
                fixed4 tex = tex2D(_MainTex, i.uv) * _Color;
                fixed3 col = tex.rgb * i.lighting + i.rim;
                col += tex.rgb * _EmissionColor.rgb * _Emission;
                fixed4 result = fixed4(col, 1);
                UNITY_APPLY_FOG(i.fogCoord, result);
                return result;
            }
            ENDCG
        }
    }
    FallBack Off
}
