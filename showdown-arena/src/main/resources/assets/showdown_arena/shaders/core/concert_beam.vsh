#version 150

in vec3 Position;
in vec2 UV0;
in vec4 Color;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec2 vUv;
out vec4 vColor;
out vec3 vNormalView;
out vec3 vViewPos;

void main() {
    vec4 viewPos = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * viewPos;
    vUv = UV0;
    vColor = Color;
    vNormalView = normalize(mat3(ModelViewMat) * Normal);
    vViewPos = viewPos.xyz;
}
