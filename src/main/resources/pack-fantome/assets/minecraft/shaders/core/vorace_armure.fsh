#version 150

// Vorace : copie de core/entity pour les armures portées (rendertype_armor_cutout_no_cull).

#moj_import <minecraft:fog.glsl>

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in float vertexDistance;
in vec4 vertexColor;
in vec4 lightMapColor;
in vec4 overlayColor;
in vec2 texCoord0;
in float voraceMenu;

out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0);
    // Vorace : les textures d'armure du pack ont leurs pixels à l'opacité 253. Hors des menus, elles sont portées par le
    // pantin du joueur : on n'en dessine aucune sur son vrai corps (il la voit seulement dans son inventaire).
    if (voraceMenu < 0.5 && abs(color.a * 255.0 - 253.0) < 0.5) {
        discard;
    }
#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif
    color *= vertexColor * ColorModulator;
#ifndef NO_OVERLAY
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
#endif
#ifndef EMISSIVE
    color *= lightMapColor;
#endif
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
