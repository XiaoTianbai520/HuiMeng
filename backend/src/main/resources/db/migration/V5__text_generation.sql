-- M3-02 text generation catalog. Disabled by default; no endpoint, secret or approval is seeded.
INSERT INTO approved_models(provider_id,model_id,label,enabled,capabilities,options_json) VALUES
('qwen','qwen-plus','Qwen Plus',FALSE,'["text","agent"]','{}'),
('qwen','qwen-turbo','Qwen Turbo',FALSE,'["text","agent"]','{}'),
('deepseek','deepseek-reasoner','DeepSeek Reasoner',FALSE,'["text","agent"]','{}');
