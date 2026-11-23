#version 150

uniform sampler2D DiffuseSampler;
uniform vec2 BlurDir;

in vec2 texCoord;
in vec2 oneTexel;

out vec4 fragColor;

void main() {
    vec2 stepUv = oneTexel * BlurDir * 1.85;
    vec3 acc = texture(DiffuseSampler, texCoord).rgb * 0.28;
    acc += texture(DiffuseSampler, texCoord + stepUv * 1.0).rgb * 0.22;
    acc += texture(DiffuseSampler, texCoord - stepUv * 1.0).rgb * 0.22;
    acc += texture(DiffuseSampler, texCoord + stepUv * 2.2).rgb * 0.14;
    acc += texture(DiffuseSampler, texCoord - stepUv * 2.2).rgb * 0.14;
    fragColor = vec4(acc, 1.0);
}
