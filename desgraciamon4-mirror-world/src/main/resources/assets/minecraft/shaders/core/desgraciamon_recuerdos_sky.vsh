#version 150

in vec3 Position;

uniform mat4 ProjMat;
uniform mat4 ModelViewMat;
uniform mat4 SkyRotMat;

out vec3 worldDir;

void main() {
    worldDir = normalize(Position);
    vec4 viewPos = SkyRotMat * vec4(Position, 0.0);
    gl_Position = (ProjMat * viewPos).xyww;
}

