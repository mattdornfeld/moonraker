from bayes_opt import BayesianOptimization

optimizer = BayesianOptimization(
    rfccv,
    {'n_estimators': (10, 250),
    'min_samples_split': (2, 25),
    'max_features': (0.1, 0.999)}
)

optimizer.maximize(n_iter=10, gp_params=dict(alpha=1e-3))