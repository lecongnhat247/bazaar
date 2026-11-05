{{- define "bazaar.configMapName" -}}
bazaar-config
{{- end -}}

{{- define "bazaar.image" -}}
{{- if .root.Values.image.registry -}}
{{ .root.Values.image.registry }}/{{ .image }}:{{ .root.Values.image.tag }}
{{- else -}}
{{ .image }}:{{ .root.Values.image.tag }}
{{- end -}}
{{- end -}}

{{- define "bazaar.selectorLabels" -}}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
{{- end -}}

{{- define "bazaar.labels" -}}
{{ include "bazaar.selectorLabels" . }}
app.kubernetes.io/part-of: bazaar
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
app.kubernetes.io/version: {{ .root.Chart.AppVersion | quote }}
helm.sh/chart: {{ printf "%s-%s" .root.Chart.Name .root.Chart.Version | replace "+" "_" }}
{{- end -}}
