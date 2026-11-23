#version 150

in vec3 Position;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat4 SkyRotMat;

out vec3 worldDir;

void main() {
    worldDir = Position;
    vec4 dummy = ModelViewMat * vec4(0.0);
    gl_Position = ProjMat * SkyRotMat * vec4(Position, 1.0) + dummy;
}

