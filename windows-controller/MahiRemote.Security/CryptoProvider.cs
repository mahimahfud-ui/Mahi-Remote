using System.Security.Cryptography;
using System.Text;

namespace MahiRemote.Security;

public class CryptoProvider
{
    private readonly ECDsa _ecdsaKey = ECDsa.Create(ECCurve.NamedCurves.nistP256);

    public string OperatorFingerprint { get; }
    public string PublicKeyBase64 { get; }

    public CryptoProvider()
    {
        byte[] pubBytes = _ecdsaKey.ExportSubjectPublicKeyInfo();
        PublicKeyBase64 = Convert.ToBase64String(pubBytes);

        using var sha = SHA256.Create();
        byte[] hash = sha.ComputeHash(pubBytes);
        OperatorFingerprint = string.Join(":", hash.Take(16).Select(b => b.ToString("X2")));
    }

    public string SignPayload(string payload)
    {
        byte[] data = Encoding.UTF8.GetBytes(payload);
        byte[] signature = _ecdsaKey.SignData(data, HashAlgorithmName.SHA256);
        return Convert.ToBase64String(signature);
    }

    public bool VerifySignature(string payload, string signatureBase64, string publicKeyBase64)
    {
        try
        {
            using var remoteKey = ECDsa.Create();
            remoteKey.ImportSubjectPublicKeyInfo(Convert.FromBase64String(publicKeyBase64), out _);
            byte[] data = Encoding.UTF8.GetBytes(payload);
            byte[] signature = Convert.FromBase64String(signatureBase64);
            return remoteKey.VerifyData(data, signature, HashAlgorithmName.SHA256);
        }
        catch
        {
            return false;
        }
    }
}
