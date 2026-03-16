import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import Editor from '@monaco-editor/react';
import { useAuthStore } from '../stores/auth';
import api from '../services/api';
import type { CodeModule } from '../types';

const TEMPLATES = {
  IMPORT: `class ImportModule:
    """
    Receives or fetches data from an external source.
    Entry node in the pipeline DAG.
    """

    def connect(self, config: dict):
        """Initialise connection. Called once on container start."""
        # Example: self.client = MQTTClient(config['broker'])
        pass

    def fetch(self) -> dict | pd.DataFrame:
        """Fetch one record or batch. Called on schedule or in a loop."""
        # Example: return {"temperature": 23.5, "timestamp": "2024-01-01T00:00:00Z"}
        pass

    def disconnect(self):
        """Clean up connection. Called on container stop."""
        pass
`,
  PROCESSING: `class ProcessingModule:
    """
    Transform, filter, or enrich incoming data.

    Return the modified data dict or DataFrame to pass it downstream.
    Return None to drop the message.
    Raise an exception to route the message to the dead-letter queue.
    """

    def process(self, data: dict | pd.DataFrame,
                query_fn=None) -> dict | pd.DataFrame | None:
        # Example: add a derived field
        # data["celsius"] = (data["fahrenheit"] - 32) * 5 / 9
        return data
`,
  ALERT: `class AlertModule:
    """
    Evaluates conditions and dispatches alerts.
    Terminal node — does NOT modify or forward the data stream.
    """

    def __init__(self, config: dict):
        self.threshold = config.get('threshold', 80)
        self.severity = config.get('severity', 'warning')

    def evaluate(self, data: dict | pd.DataFrame,
                 alert_fn=None,
                 query_fn=None) -> bool:
        """
        Evaluate alert condition.
        Call alert_fn(severity, message, payload) to fire an alert.
        Return True if alert fired, False otherwise.
        """
        # Example:
        # if data['value'] > self.threshold:
        #     alert_fn(severity=self.severity,
        #              message=f"Value {data['value']} exceeds threshold",
        #              payload=data)
        #     return True
        return False
`,
  EXPORT: `class ExportModule:
    """
    Sends data to external systems or exposes pull endpoints.
    Terminal node — does not forward data downstream.
    """

    def connect(self, config: dict):
        """Open connection or register endpoint."""
        # Example: self.client = RESTClient(config['url'])
        pass

    def send(self, data: dict | pd.DataFrame):
        """Push data to destination or update the exposed endpoint's buffer."""
        # Example: self.client.post(data)
        pass

    def disconnect(self):
        """Clean up or deregister endpoint."""
        pass
`
};

export default function ModuleEditor() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { user } = useAuthStore();
  
  const [module, setModule] = useState<CodeModule | null>(null);
  const [code, setCode] = useState('');
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
          setCode(data.versions[0].code || TEMPLATES[data.moduleType as keyof typeof TEMPLATES] || TEMPLATES.PROCESSING);
          setRequirements(data.versions[0].requirements || 'pandas\nnumpy');
        } else {
          // Set template based on module type
          setCode(TEMPLATES[data.moduleType as keyof typeof TEMPLATES] || TEMPLATES.PROCESSING);
        }
      } catch (error) {
        console.error('Error fetching module:', error);
        setCode(TEMPLATES.PROCESSING);
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
            <p className="text-xs text-gray-600 mb-2">
              {module.moduleType === 'IMPORT' && 'IMPORT modules receive or fetch data from external sources.'}
              {module.moduleType === 'PROCESSING' && 'PROCESSING modules transform, filter, or enrich data.'}
              {module.moduleType === 'ALERT' && 'ALERT modules evaluate conditions and dispatch alerts.'}
              {module.moduleType === 'EXPORT' && 'EXPORT modules send data to external systems.'}
            </p>
            <div className="flex space-x-2 mb-2">
              {Object.keys(TEMPLATES).map((type) => (
                <button
                  key={type}
                  onClick={() => setCode(TEMPLATES[type as keyof typeof TEMPLATES])}
                  className={`text-xs px-2 py-1 rounded ${
                    code === TEMPLATES[type as keyof typeof TEMPLATES]
                      ? 'bg-primary-100 text-primary-800'
                      : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
                  }`}
                >
                  {type}
                </button>
              ))}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
