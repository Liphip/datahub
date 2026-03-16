import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import Editor from '@monaco-editor/react';
import { useAuthStore } from '../stores/auth';
import api from '../services/api';
import type { CodeModule } from '../types';

export default function ModuleEditor() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { user } = useAuthStore();
  
  const [module, setModule] = useState<CodeModule | null>(null);
  const [code, setCode] = useState('# Your Python module code here\n\nclass ProcessingModule:\n    def process(self, data, alert_fn=None):\n        # Transform your data here\n        return data\n');
  const [requirements, setRequirements] = useState('pandas\nnumpy');
  const [versionTag, setVersionTag] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  const tenantId = user?.tenantId || user?.id;

  useEffect(() => {
    const fetchModule = async () => {
      if (!id) return;
      
      try {
        const data = await api.getModule(id);
        setModule(data);
        
        // Load latest version code if available
        if (data.versions && data.versions.length > 0) {
          setCode(data.versions[0].code || '# Your Python code here\n');
          setRequirements(data.versions[0].requirements || 'pandas\nnumpy');
        }
      } catch (error) {
        console.error('Error fetching module:', error);
      } finally {
        setLoading(false);
      }
    };
    
    fetchModule();
  }, [id]);

  const handleSave = async () => {
    if (!id || !tenantId) return;
    
    setSaving(true);
    try {
      await api.createModuleVersion(id, code, requirements, versionTag || undefined);
      alert('Version saved successfully!');
    } catch (error) {
      console.error('Error saving version:', error);
      alert('Failed to save version');
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center h-screen">
        <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-primary-600"></div>
      </div>
    );
  }

  if (!module) {
    return (
      <div className="text-center py-12">
        <p className="text-gray-500">Module not found</p>
        <button onClick={() => navigate('/modules')} className="btn-primary mt-4">
          Back to Modules
        </button>
      </div>
    );
  }

  return (
    <div className="h-screen flex flex-col">
      <div className="flex justify-between items-center p-4 bg-white border-b">
        <div>
          <h1 className="text-xl font-bold">{module.name}</h1>
          <p className="text-sm text-gray-500">{module.moduleType} Module</p>
        </div>
        <div className="flex space-x-3">
          <button
            onClick={() => navigate('/modules')}
            className="btn-secondary"
          >
            Cancel
          </button>
          <button
            onClick={handleSave}
            disabled={saving}
            className="btn-primary"
          >
            {saving ? 'Saving...' : 'Save Version'}
          </button>
        </div>
      </div>

      <div className="flex-1 flex">
        <div className="w-3/4 border-r">
          <Editor
            height="100%"
            defaultLanguage="python"
            value={code}
            onChange={(value) => setCode(value || '')}
            theme="vs-dark"
            options={{
              minimap: { enabled: false },
              fontSize: 14,
              lineNumbers: 'on',
              scrollBeyondLastLine: false,
              automaticLayout: true,
            }}
          />
        </div>
        
        <div className="w-1/4 p-4 bg-gray-50 overflow-y-auto">
          <h3 className="font-semibold mb-4">Module Configuration</h3>
          
          <div className="mb-4">
            <label className="block text-sm font-medium text-gray-700 mb-1">
              Requirements (pip)
            </label>
            <textarea
              value={requirements}
              onChange={(e) => setRequirements(e.target.value)}
              className="input h-32 font-mono text-sm"
              placeholder="pandas&#10;numpy&#10;scikit-learn"
            />
          </div>
          
          <div className="mb-4">
            <label className="block text-sm font-medium text-gray-700 mb-1">
              Version Tag (optional)
            </label>
            <input
              type="text"
              value={versionTag}
              onChange={(e) => setVersionTag(e.target.value)}
              className="input"
              placeholder="e.g., v1.0, production, stable"
            />
          </div>

          <div className="border-t pt-4 mt-4">
            <h4 className="font-medium text-sm mb-2">Version History</h4>
            {module.versions.length > 0 ? (
              <div className="space-y-2">
                {module.versions.map((v) => (
                  <div key={v.id} className="p-2 bg-white rounded border text-sm">
                    <div className="flex justify-between">
                      <span className="font-medium">v{v.version}</span>
                      {v.versionTag && (
                        <span className="text-xs bg-gray-100 px-2 py-0.5 rounded">
                          {v.versionTag}
                        </span>
                      )}
                    </div>
                    <div className="text-xs text-gray-500">
                      {new Date(v.createdAt).toLocaleString()}
                    </div>
                  </div>
                ))}
              </div>
            ) : (
              <p className="text-sm text-gray-500">No versions yet</p>
            )}
          </div>

          <div className="border-t pt-4 mt-4">
            <h4 className="font-medium text-sm mb-2">Module Template</h4>
            <pre className="text-xs bg-white p-2 rounded border overflow-x-auto">
{`class ProcessingModule:
    def process(self, data, alert_fn=None):
        # data is dict or DataFrame
        # alert_fn(severity, message, payload)
        return data

# Or use functional style:
def process(data):
    return data
`}
            </pre>
          </div>
        </div>
      </div>
    </div>
  );
}
