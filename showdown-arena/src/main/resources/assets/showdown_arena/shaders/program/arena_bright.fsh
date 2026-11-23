#version 150

uniform sampler2D DiffuseSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec3 col = texture(DiffuseSampler, texCoord).rgb;
    float lum = dot(col, vec3(0.2126, 0.7152, 0.0722));
    float maxC = max(col.r, max(col.g, col.b));
    float energy = max(lum, maxC * 0.85);
    float weight = smoothstep(0.76, 0.98, energy);
    fragColor = vec4(col * weight, 1.0);
}
