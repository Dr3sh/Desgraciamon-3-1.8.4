#version 150

uniform sampler2D DiffuseSampler;
uniform sampler2D BloomSampler;
uniform float BloomIntensity;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec4 base = texture(DiffuseSampler, texCoord);
    vec3 bloom = texture(BloomSampler, texCoord).rgb;
    vec3 combined = base.rgb + bloom * BloomIntensity;
    fragColor = vec4(clamp(combined, 0.0, 1.0), base.a);
}
