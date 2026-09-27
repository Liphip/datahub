import type { EnrichConfig, MetadataType, SaveMetadataMapping } from '../types';
import { splitList } from '../lib/metadataNodes';

const input = 'border rounded-sm px-2 py-1 text-sm w-full';
const labelCls = 'block text-xs font-medium text-gray-600 mb-1';
const hint = 'text-[10px] text-gray-400 mt-1';

const schemesOf = (types: MetadataType[]) =>
  types.flatMap((t) => t.identifierSchemes.map((s) => ({ scheme: s.scheme, label: `${s.name ?? s.scheme} (${t.name})` })));

export function EnrichForm({ config, types, disabled, onChange }: {
  config: EnrichConfig;
  types: MetadataType[];
  disabled: boolean;
  onChange: (config: EnrichConfig) => void;
}) {
  const setOutput = (i: number, patch: Partial<EnrichConfig['outputs'][number]>) =>
    onChange({ ...config, outputs: config.outputs.map((o, j) => (j === i ? { ...o, ...patch } : o)) });

  return (
    <div className="space-y-3">
      <div>
        <label className={labelCls}>Find object by</label>
        <select className={input} disabled={disabled} value={config.match.scheme} onChange={(e) => onChange({ ...config, match: { ...config.match, scheme: e.target.value } })}>
          <option value="">— identifier scheme —</option>
          {schemesOf(types).map((s) => <option key={s.scheme} value={s.scheme}>{s.label}</option>)}
        </select>
      </div>
      <div>
        <label className={labelCls}>Record field with the identifier</label>
        <input className={input} disabled={disabled} placeholder="devEUI" value={config.match.field}
          onChange={(e) => onChange({ ...config, match: { ...config.match, field: e.target.value } })} />
      </div>
      <div>
        <label className={labelCls}>Record time field (optional)</label>
        <input className={input} disabled={disabled} placeholder="time" value={config.timestampField ?? ''}
          onChange={(e) => onChange({ ...config, timestampField: e.target.value || undefined })} />
        <p className={hint}>Metadata valid at this time is used, so a meter exchanged last month enriches old readings with the old meter. Empty: now.</p>
      </div>
      <div className="space-y-2">
        <span className={labelCls}>Add to each record</span>
        {config.outputs.map((o, i) => (
          <div key={i} className="rounded-sm border border-gray-200 p-2 space-y-1">
            <span className="block text-[10px] text-gray-500">Record field</span>
            <input className={input} disabled={disabled} placeholder="e.g. location" value={o.as} onChange={(e) => setOutput(i, { as: e.target.value })} />
            <span className="block text-[10px] text-gray-500">Follow relations (optional)</span>
            <input className={input} disabled={disabled} placeholder="e.g. market_location, location" value={(o.path ?? []).join(', ')}
              onChange={(e) => setOutput(i, { path: splitList(e.target.value) })} />
            {config.outputs.length > 1 && (
              <button type="button" disabled={disabled} className="text-[11px] text-red-600" onClick={() => onChange({ ...config, outputs: config.outputs.filter((_, j) => j !== i) })}>Remove</button>
            )}
          </div>
        ))}
        <button type="button" disabled={disabled} className="text-xs text-primary-600" onClick={() => onChange({ ...config, outputs: [...config.outputs, { as: '' }] })}>+ Add output</button>
        <p className={hint}>Without relations, the matched object is added. With relations, the object reached by following them, e.g. meter → market location → location.</p>
      </div>
      <div>
        <label className={labelCls}>No metadata found</label>
        <select className={input} disabled={disabled} value={config.onMissing ?? 'keep'} onChange={(e) => onChange({ ...config, onMissing: e.target.value as EnrichConfig['onMissing'] })}>
          <option value="keep">Keep record without metadata</option>
          <option value="drop">Drop record</option>
          <option value="fail">Fail the run</option>
        </select>
      </div>
    </div>
  );
}

export function SaveMetadataForm({ config, types, disabled, onChange }: {
  config: SaveMetadataMapping;
  types: MetadataType[];
  disabled: boolean;
  onChange: (config: SaveMetadataMapping) => void;
}) {
  const type = types.find((t) => t.typeKey === config.type);
  const relations = config.relations ?? [];
  const setRelation = (relation: string, field: string) => {
    const r = type?.relationSchema.find((x) => x.relation === relation);
    const target = types.find((t) => t.typeKey === r?.targetType);
    const rest = relations.filter((x) => x.relation !== relation);
    onChange({
      ...config,
      relations: field && target
        ? [...rest, { relation, type: target.typeKey, scheme: target.identifierSchemes[0]?.scheme ?? '', field, create: true }]
        : rest,
    });
  };
  const allAttributes = config.attributes === '*';

  return (
    <div className="space-y-3">
      <div>
        <label className={labelCls}>Metadata type</label>
        <select className={input} disabled={disabled} value={config.type}
          onChange={(e) => {
            const next = types.find((t) => t.typeKey === e.target.value);
            onChange({ ...config, type: e.target.value, match: { ...config.match, scheme: next?.identifierSchemes[0]?.scheme ?? '' }, relations: [] });
          }}>
          <option value="">— type —</option>
          {types.map((t) => <option key={t.id} value={t.typeKey}>{t.name}</option>)}
        </select>
        {types.length === 0 && <p className="text-xs text-amber-600 mt-1">No metadata types yet. Install a template on the Metadata page.</p>}
      </div>
      {type && (
        <>
          <div>
            <label className={labelCls}>Identified by</label>
            <select className={input} disabled={disabled} value={config.match.scheme} onChange={(e) => onChange({ ...config, match: { ...config.match, scheme: e.target.value } })}>
              {type.identifierSchemes.map((s) => <option key={s.scheme} value={s.scheme}>{s.name ?? s.scheme}</option>)}
            </select>
            <input className={`${input} mt-1`} disabled={disabled} placeholder="record field, e.g. EQUNR" value={config.match.field}
              onChange={(e) => onChange({ ...config, match: { ...config.match, field: e.target.value } })} />
          </div>
          <div>
            <label className={labelCls}>Name field (optional)</label>
            <input className={input} disabled={disabled} value={config.nameField ?? ''} onChange={(e) => onChange({ ...config, nameField: e.target.value || undefined })} />
          </div>
          <div>
            <label className="flex items-center gap-2 text-xs text-gray-600">
              <input type="checkbox" disabled={disabled} checked={allAttributes}
                onChange={(e) => onChange({ ...config, attributes: e.target.checked ? '*' : Object.fromEntries(type.attributeSchema.map((a) => [a.name, a.name])) })} />
              Store all record fields as attributes
            </label>
            {!allAttributes && type.attributeSchema.map((a) => (
              <label key={a.name} className="grid grid-cols-[6rem_1fr] items-center gap-1 mt-1 text-xs">
                <span className="truncate text-gray-600" title={a.name}>{a.name}</span>
                <input className={input} disabled={disabled} placeholder="record field"
                  value={(config.attributes as Record<string, string> | undefined)?.[a.name] ?? ''}
                  onChange={(e) => {
                    const next = { ...(config.attributes as Record<string, string>) };
                    if (e.target.value) next[a.name] = e.target.value; else delete next[a.name];
                    onChange({ ...config, attributes: next });
                  }} />
              </label>
            ))}
          </div>
          {type.relationSchema.length > 0 && (
            <div>
              <span className={labelCls}>Relations (record field with the target's identifier)</span>
              {type.relationSchema.map((r) => (
                <label key={r.relation} className="grid grid-cols-[6rem_1fr] items-center gap-1 mt-1 text-xs">
                  <span className="truncate text-gray-600" title={`${r.relation} → ${r.targetType}`}>{r.relation}</span>
                  <input className={input} disabled={disabled || !types.some((t) => t.typeKey === r.targetType)} placeholder="record field"
                    value={relations.find((x) => x.relation === r.relation)?.field ?? ''} onChange={(e) => setRelation(r.relation, e.target.value)} />
                </label>
              ))}
              <p className={hint}>Missing targets are created, so the SAP export can be loaded before the location list.</p>
            </div>
          )}
          <div>
            <label className={labelCls}>Valid-from field (optional)</label>
            <input className={input} disabled={disabled} value={config.validFromField ?? ''} onChange={(e) => onChange({ ...config, validFromField: e.target.value || undefined })} />
            <p className={hint}>Changes apply from this date; earlier readings keep the previous metadata. Saving the same data again changes nothing.</p>
          </div>
        </>
      )}
    </div>
  );
}
