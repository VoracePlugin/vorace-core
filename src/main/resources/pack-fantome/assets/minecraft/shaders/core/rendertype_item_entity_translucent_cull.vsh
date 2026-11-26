#version 150

#moj_import <minecraft:light.glsl>
#moj_import <minecraft:fog.glsl>

// Vorace : corps fantôme du joueur (pantin permanent). Un joueur invisible vu par son équipe est dessiné avec
// une opacité de 38/255 : chez lui, c'est son propre corps. Dans un menu (projection orthographique), on le rend
// opaque : il se voit dans son inventaire. Dans le monde, on l'efface : il voit son pantin.
//
// Bras de sa propre copie du pantin (Vorace, MarqueSoi) : leur teinte porte une marque (deux bits bas des couleurs à
// 1, 2, 1). À la première personne, la caméra est dans la tête, tout près d'eux : on les efface, la main du jeu tient
// l'arme (comme « Vanilla Hands » de First-person Model). En F5, la caméra est à 4 blocs : le pantin est entier.

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in vec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;

uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;

out float vertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;
out vec2 texCoord1;
out vec2 texCoord2;
out float voraceFantome;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    vertexDistance = fog_distance(Position, FogShape);
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color) * texelFetch(Sampler2, UV2 / 16, 0);
    texCoord0 = UV0;
    texCoord1 = UV1;
    texCoord2 = UV2;

    voraceFantome = 0.0;
    bool menu = ProjMat[2][3] == 0.0;
    if (abs(Color.a * 255.0 - 38.0) < 0.5) {
        if (menu) vertexColor.a = 1.0;
        else voraceFantome = 1.0;
    }
    ivec3 c = ivec3(round(Color.rgb * 255.0));
    if (!menu && (c.r & 3) == 1 && (c.g & 3) == 2 && (c.b & 3) == 1 && length(Position) < 1.75) {
        voraceFantome = 1.0;
    }
}
